package ai.mindconnect.agent.runtime.capabilities.toolresult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.hook.AgentContext;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.hook.ToolInvocation;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * A result that is too long when it is produced is cut before it is kept;
 * one that memory set aside later is read again from the session.
 */
class ToolResultPluginTest {

    private static final AgentDefinition AGENT = new AgentDefinition("analyst", "Analyses.", "You analyse.",
            ModelSettings.NONE,
            List.of(new ToolBinding("bash", false, Map.of(ToolResultPlugin.MAX_RESULT_CHARS, 10)),
                    ToolBinding.of("web_search"), ToolBinding.of(ToolResultPlugin.FETCH_TOOL)),
            5, Map.of());
    private static final Session SESSION = new Session(SessionId.of("s1"), null, null, AGENT, Map.of(),
            Instant.EPOCH);
    private static final List<TurnMessage> HISTORY = List.of(
            new TurnMessage("m1", "t", TurnMessage.Type.USER, List.of(new ContentPart.Text("list the files")),
                    List.of(), Map.of()),
            // As the store hands it out: the result in full, the note memory shows in its place beside it.
            new TurnMessage("m2", "t", TurnMessage.Type.TOOL_RESULT,
                    List.of(new ContentPart.Text("a.txt\nb.txt\nc.txt")), List.of(),
                    Map.of(SessionStore.COMPRESSED, "[Tool result removed to save context: 6 tokens, id=m2]")));

    private final PluginRegistry registry = new DefaultPluginRegistry();

    ToolResultPluginTest() {
        registry.provide(SessionStore.class, ToolResultPluginTest::store);
        registry.install(new ToolResultPlugin(60));
    }

    @Test
    void a_result_past_the_limit_of_its_binding_is_cut_and_says_so() {
        ToolOutput produced = produced("bash", ToolOutput.ok("0123456789ABCDEF"));

        assertThat(produced).isEqualTo(ToolOutput.ok("0123456789\n… [output truncated after 10 characters]"));
    }

    @Test
    void a_tool_without_a_limit_of_its_own_stops_at_the_cap_of_the_runtime() {
        assertThat(produced("web_search", ToolOutput.ok("x".repeat(60)))).isEqualTo(ToolOutput.ok("x".repeat(60)));
        assertThat(produced("web_search", ToolOutput.ok("x".repeat(61))))
                .isEqualTo(ToolOutput.ok("x".repeat(60) + "\n… [output truncated after 60 characters]"));
    }

    @Test
    void a_binding_cannot_allow_more_than_the_runtime() {
        AgentDefinition generous = new AgentDefinition("generous", "", "", ModelSettings.NONE,
                List.of(new ToolBinding("bash", false, Map.of(ToolResultPlugin.MAX_RESULT_CHARS, 1_000))), 5,
                Map.of());

        ToolOutput produced = registry.run(RuntimeHook.TOOL_CALL, invocation(generous, "bash", Map.of()),
                running -> ToolOutput.ok("x".repeat(70)));

        assertThat(((ToolOutput.Completed) produced).text()).startsWith("x".repeat(60) + "\n…");
    }

    @Test
    void a_failure_stays_one_and_what_is_no_result_is_left_alone() {
        assertThat(produced("bash", ToolOutput.failed("Error: 0123456789ABCDEF")))
                .isEqualTo(ToolOutput.failed("Error: 012\n… [output truncated after 10 characters]"));
        ToolOutput asks = ToolOutput.interrupt("tool_approval", Map.of());
        assertThat(produced("bash", asks)).isSameAs(asks);
    }

    @Test
    void a_cap_that_is_none_is_refused() {
        assertThatThrownBy(() -> new ToolResultPlugin(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void an_agent_that_names_the_tool_reads_a_removed_result_again_in_full() {
        ToolResolver tools = ToolResolver.firstOf(() -> registry.all(ToolResolver.class));

        assertThat(tools.tools(AGENT, SESSION)).extracting(ToolDefinition::name)
                .containsExactly(ToolResultPlugin.FETCH_TOOL);
        assertThat(fetch("m2")).isEqualTo(ToolOutput.ok("a.txt\nb.txt\nc.txt"));
    }

    @Test
    void an_id_that_names_no_result_of_the_session_is_an_error_the_model_reads() {
        assertThat(fetch("m1")).isEqualTo(ToolOutput.failed("Error: message m1 is not a tool result"));
        assertThat(fetch("elsewhere")).isEqualTo(
                ToolOutput.failed("Error: no message with id elsewhere in this session"));
        assertThat(fetch(" ")).isEqualTo(ToolOutput.failed("Error: id is required"));
    }

    private ToolOutput produced(String tool, ToolOutput output) {
        return registry.run(RuntimeHook.TOOL_CALL, invocation(AGENT, tool, Map.of()), running -> output);
    }

    /** As the engine runs a call: the tool is looked up and run inside the hook. */
    private ToolOutput fetch(String id) {
        ToolInvocation invocation = invocation(AGENT, ToolResultPlugin.FETCH_TOOL, Map.of("id", id));
        return registry.run(RuntimeHook.TOOL_CALL, invocation, running ->
                ToolResolver.firstOf(() -> registry.all(ToolResolver.class))
                        .resolve(running.call().toolName(), AGENT, SESSION).orElseThrow()
                        .execute(running.call(), SESSION));
    }

    private static ToolInvocation invocation(AgentDefinition agent, String tool, Map<String, Object> arguments) {
        TurnId turn = TurnId.of("t1");
        return new ToolInvocation(new AgentContext(SESSION, agent, turn),
                new ToolCall("c1", tool, arguments, turn, SESSION.id()), Optional.empty());
    }

    private static SessionStore store() {
        return new SessionStore() {
            @Override
            public void create(Session session) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<Session> find(SessionId sessionId) {
                return Optional.of(SESSION);
            }

            @Override
            public Session update(SessionId sessionId, UnaryOperator<Session> change) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<TurnMessage> messages(SessionId sessionId) {
                return sessionId.equals(SESSION.id()) ? HISTORY : List.of();
            }

            @Override
            public List<TurnMessage> append(SessionId sessionId, List<TurnMessage> messages) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void updateMessageMetadata(SessionId sessionId, String messageId,
                                              UnaryOperator<Map<String, Object>> change) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
