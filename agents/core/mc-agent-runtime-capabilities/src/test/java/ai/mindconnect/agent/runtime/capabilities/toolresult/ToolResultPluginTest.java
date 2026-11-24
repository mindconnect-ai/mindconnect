package ai.mindconnect.agent.runtime.capabilities.toolresult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.loop.domain.Usage;
import ai.mindconnect.agent.runtime.api.hook.AgentContext;
import ai.mindconnect.agent.runtime.api.hook.ModelCall;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.hook.ToolInvocation;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * The model reads a long result a page at a time and reads on by its id; the
 * session keeps the result whole, up to the cap of the runtime.
 */
class ToolResultPluginTest {

    /** Shown 20 characters of a result at a time, 10 of what bash returns. */
    private static final AgentDefinition AGENT = new AgentDefinition("analyst", "Analyses.", "You analyse.",
            ModelSettings.NONE,
            List.of(new ToolBinding("bash", false, Map.of(ToolResultPlugin.MAX_RESULT_CHARS, 10)),
                    ToolBinding.of("web_search")),
            5, Map.of(ToolResultPlugin.MAX_RESULT_CHARS, 20));
    private static final Session SESSION = new Session(SessionId.of("s1"), null, null, AGENT, Map.of(),
            Instant.EPOCH);
    private static final String LONG = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final List<TurnMessage> HISTORY = List.of(
            new TurnMessage("m1", "t", TurnMessage.Type.USER, List.of(new ContentPart.Text("list the files")),
                    List.of(), Map.of()),
            // As the store hands it out: the result in full, the note memory shows in its place beside it.
            new TurnMessage("m2", "t", TurnMessage.Type.TOOL_RESULT,
                    List.of(new ContentPart.Text("a.txt\nb.txt\nc.txt")), List.of(),
                    Map.of(SessionStore.COMPRESSED, "[Tool result removed to save context: 6 tokens, id=m2]")),
            result("m3", "web_search", LONG),
            result("m4", "bash", LONG),
            result("m5", "web_search", "short"));

    private final PluginRegistry registry = new DefaultPluginRegistry();

    ToolResultPluginTest() {
        registry.provide(SessionStore.class, ToolResultPluginTest::store);
        registry.install(new ToolResultPlugin(100));
    }

    @Test
    void the_model_is_shown_a_page_of_a_long_result_and_how_to_read_on() {
        List<String> shown = shownToTheModel();

        assertThat(shown.get(2)).isEqualTo("0123456789abcdefghij"
                + "\n… [showing characters 0-20 of 36. Read on with fetch_tool_result: id=m3, offset=20]");
        assertThat(shown.get(4)).as("a result within the page is shown as it is").isEqualTo("short");
        assertThat(shown.get(1)).as("what memory shows as a note is memory's").isEqualTo("a.txt\nb.txt\nc.txt");
    }

    @Test
    void a_tool_with_a_limit_of_its_own_has_a_page_of_that_size() {
        assertThat(shownToTheModel().get(3)).startsWith("0123456789\n… [showing characters 0-10 of 36.");
    }

    @Test
    void an_agent_that_names_no_limit_is_shown_the_default_page() {
        AgentDefinition plain = new AgentDefinition("plain", "", "", ModelSettings.NONE,
                List.of(ToolBinding.of("web_search")), 5, Map.of());

        assertThat(ToolResultPlugin.pageChars(plain, "web_search")).isEqualTo(ToolResultPlugin.DEFAULT_PAGE_CHARS);
    }

    @Test
    void reading_on_returns_the_next_page_and_says_where_the_one_after_starts() {
        assertThat(fetch(Map.of("id", "m3", "offset", 20))).isEqualTo(ToolOutput.ok("klmnopqrstuvwxyz"));
        assertThat(fetch(Map.of("id", "m4", "offset", 10))).isEqualTo(ToolOutput.ok(
                "abcdefghij\n… [characters 10-20 of 36. Read on with offset=20]"));
        assertThat(fetch(Map.of("id", "m3", "offset", 0, "limit", 5))).isEqualTo(ToolOutput.ok(
                "01234\n… [characters 0-5 of 36. Read on with offset=5]"));
    }

    @Test
    void reading_on_never_returns_more_than_a_page_and_refuses_an_offset_past_the_end() {
        assertThat(fetch(Map.of("id", "m3", "limit", 1_000))).isEqualTo(ToolOutput.ok(
                "0123456789abcdefghij\n… [characters 0-20 of 36. Read on with offset=20]"));
        assertThat(fetch(Map.of("id", "m3", "offset", 36))).isEqualTo(ToolOutput.failed(
                "Error: offset 36 is past the end of the result (36 characters)"));
    }

    @Test
    void what_the_fetch_tool_returned_is_a_page_already() {
        ModelRequest request = new ModelRequest(TurnId.of("t1"), ModelSettings.NONE, "You analyse.",
                List.of(result("m9", ToolResultPlugin.FETCH_TOOL, LONG)), List.of());

        assertThat(shownToTheModel(request)).containsExactly(LONG);
    }

    @Test
    void whoever_has_tools_is_offered_the_fetch_tool_and_an_agent_without_tools_is_not() {
        AgentDefinition bare = new AgentDefinition("bare", "", "", ModelSettings.NONE, List.of(), 5, Map.of());

        assertThat(offered(AGENT)).extracting(ToolDefinition::name).containsExactly(ToolResultPlugin.FETCH_TOOL);
        assertThat(offered(bare)).isEmpty();
    }

    @Test
    void a_result_past_the_cap_of_the_runtime_is_cut_before_it_is_kept_and_says_so() {
        assertThat(produced("web_search", ToolOutput.ok("x".repeat(100)))).isEqualTo(ToolOutput.ok("x".repeat(100)));
        assertThat(produced("web_search", ToolOutput.ok("x".repeat(101))))
                .isEqualTo(ToolOutput.ok("x".repeat(100) + "\n… [output truncated after 100 characters]"));
        // The limit of a binding is a page for the model, not a cut: the session keeps the result.
        assertThat(produced("bash", ToolOutput.ok(LONG))).isEqualTo(ToolOutput.ok(LONG));
    }

    @Test
    void a_failure_stays_one_and_what_is_no_result_is_left_alone() {
        assertThat(produced("bash", ToolOutput.failed("Error: " + "x".repeat(100))))
                .isEqualTo(ToolOutput.failed("Error: " + "x".repeat(93)
                        + "\n… [output truncated after 100 characters]"));
        ToolOutput asks = ToolOutput.interrupt("tool_approval", Map.of());
        assertThat(produced("bash", asks)).isSameAs(asks);
    }

    @Test
    void a_cap_that_is_none_is_refused() {
        assertThatThrownBy(() -> new ToolResultPlugin(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_result_memory_removed_is_read_again_by_the_id_its_note_names() {
        assertThat(fetch(Map.of("id", "m2"))).isEqualTo(ToolOutput.ok("a.txt\nb.txt\nc.txt"));
    }

    @Test
    void an_id_that_names_no_result_of_the_session_is_an_error_the_model_reads() {
        assertThat(fetch(Map.of("id", "m1"))).isEqualTo(ToolOutput.failed("Error: message m1 is not a tool result"));
        assertThat(fetch(Map.of("id", "elsewhere"))).isEqualTo(
                ToolOutput.failed("Error: no message with id elsewhere in this session"));
        assertThat(fetch(Map.of("id", " "))).isEqualTo(ToolOutput.failed("Error: id is required"));
    }

    private static TurnMessage result(String id, String tool, String text) {
        return new TurnMessage(id, "t", TurnMessage.Type.TOOL_RESULT, List.of(new ContentPart.Text(text)),
                List.of(), Map.of("callId", "c-" + id, "toolName", tool));
    }

    private List<String> shownToTheModel() {
        return shownToTheModel(new ModelRequest(TurnId.of("t1"), ModelSettings.NONE, "You analyse.", HISTORY,
                List.of()));
    }

    /** The texts of the messages the model call goes out with. */
    private List<String> shownToTheModel(ModelRequest request) {
        List<ModelRequest> sent = new ArrayList<>();
        registry.run(RuntimeHook.MODEL_CALL, new ModelCall(new AgentContext(SESSION, AGENT, TurnId.of("t1")), request),
                call -> {
                    sent.add(call.request());
                    return LlmAnswer.of(List.of(), new Usage(0, 0));
                });
        return sent.get(0).messages().stream().map(TurnMessage::text).toList();
    }

    private List<ToolDefinition> offered(AgentDefinition agent) {
        return registry.run(RuntimeHook.TOOL_OFFER, new AgentContext(SESSION, agent, TurnId.of("t1")),
                context -> List.<ToolDefinition>of());
    }

    private ToolOutput produced(String tool, ToolOutput output) {
        return registry.run(RuntimeHook.TOOL_CALL, invocation(AGENT, tool, Map.of()), running -> output);
    }

    /** As the engine runs a call: the tool is looked up and run inside the hook. */
    private ToolOutput fetch(Map<String, Object> arguments) {
        ToolInvocation invocation = invocation(AGENT, ToolResultPlugin.FETCH_TOOL, arguments);
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
