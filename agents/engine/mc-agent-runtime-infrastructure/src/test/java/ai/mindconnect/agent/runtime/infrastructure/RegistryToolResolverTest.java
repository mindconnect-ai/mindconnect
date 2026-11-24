package ai.mindconnect.agent.runtime.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ai.mindconnect.agent.management.tool.AgentToolBinding;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.tool.UserToolRoster;

/**
 * In the session a user chats in, the tools they keep in their own account
 * are laid over the agent's; a sub-agent keeps what its definition gives it.
 */
class RegistryToolResolverTest {

    private static final AgentDefinition HELPER = new AgentDefinition("helper", "Helps.", "You help.",
            ModelSettings.NONE,
            List.of(AgentToolBinding.of(AgentTool.of("bash")), AgentToolBinding.of(AgentTool.of("web_search"))),
            5, Map.of());
    private static final Session CHAT = new Session(SessionId.of("s1"), UserId.of("ada"), null, HELPER, Map.of(),
            Instant.EPOCH);
    private static final Session UNDER_A_CHAT = new Session(SessionId.of("s2"), UserId.of("ada"), SessionId.of("s1"),
            HELPER, Map.of(), Instant.EPOCH);

    /** Every tool there is resolves; a tool is what its entry names. */
    private final ToolRegistry everything = (entry, scope) -> Optional.of(new Tool() {
        @Override public String name() { return entry.name(); }
        @Override public String description() { return "Does " + entry.name() + "."; }
        @Override public Map<String, Object> parametersSchema() { return Map.of(); }
        @Override public String execute(Map<String, Object> arguments) { return entry.name() + " ran"; }
    });

    /** Ada keeps a mail tool of her own, switched web_search off and wants to be asked about bash. */
    private final UserToolRoster adas = (user, agent, named) -> {
        List<AgentTool> own = new ArrayList<>();
        for (AgentTool tool : named) {
            if (tool.name().equals("web_search")) {
                continue;
            }
            own.add(tool.name().equals("bash")
                    ? new AgentTool(tool.id(), tool.name(), tool.description(), tool.overrides(), true,
                            tool.deferred(), true, tool.maxResultChars())
                    : tool);
        }
        own.add(AgentTool.of("mail_search"));
        return own;
    };

    private final RegistryToolResolver resolver = new RegistryToolResolver(() -> everything, () -> adas);

    @Test
    void a_chat_has_what_its_user_added_and_lacks_what_they_switched_off() {
        assertThat(resolver.tools(HELPER, CHAT)).extracting(ToolDefinition::name)
                .containsExactly("bash", "mail_search");
        assertThat(resolver.added(HELPER, CHAT)).as("what the offer does not hold by the agent's naming it")
                .extracting(ToolDefinition::name).containsExactly("mail_search");
        assertThat(resolver.resolve("mail_search", HELPER, CHAT)).isPresent();
        assertThat(resolver.resolve("web_search", HELPER, CHAT)).as("switched off by the user").isEmpty();
    }

    @Test
    void a_tool_the_user_wants_to_be_asked_about_asks() {
        assertThat(resolver.asking(HELPER, CHAT)).containsExactly("bash");
        assertThat(resolver.asking(HELPER, UNDER_A_CHAT)).isEmpty();
    }

    @Test
    void a_sub_agent_keeps_the_tools_its_definition_gives_it() {
        assertThat(resolver.tools(HELPER, UNDER_A_CHAT)).extracting(ToolDefinition::name)
                .containsExactly("bash", "web_search");
        assertThat(resolver.added(HELPER, UNDER_A_CHAT)).isEmpty();
        assertThat(resolver.resolve("mail_search", HELPER, UNDER_A_CHAT)).isEmpty();
    }

    @Test
    void where_nobody_keeps_tools_of_their_own_the_agents_are_all_there_is() {
        RegistryToolResolver plain = new RegistryToolResolver(() -> everything, UserToolRoster::none);

        assertThat(plain.tools(HELPER, CHAT)).extracting(ToolDefinition::name).containsExactly("bash", "web_search");
        assertThat(plain.added(HELPER, CHAT)).isEmpty();
        assertThat(plain.asking(HELPER, CHAT)).isEmpty();
    }
}
