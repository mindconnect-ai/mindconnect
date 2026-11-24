package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.chatui.ChatFixture;
import ai.mindconnect.chatui.ui.component.ChatSettingsComponent.Values;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Picking an agent in the chat's settings redraws the model and the prompt
 * with what Apply will use — not the previous agent's prompt, which Apply
 * would ignore for a different agent.
 */
class ChatSettingsAgentPickTest {

    private static final ManagedAgent DEFAULT_CHAT =
            ChatFixture.agent("default-chat", "d", "You are a skilled assistant.", "agent-default");
    private static final ManagedAgent SECRETARY =
            ChatFixture.agent("secretary", "d", "You are the user's secretary.", "claude-default");

    private static final Values CURRENT = new Values("agent-default", "You are a skilled assistant. Be brief.");
    private static final Values SUBMITTED = new Values("openai-default", "My own prompt");

    @Test
    void aDifferentAgentBringsItsOwnModelAndPrompt() {
        assertThat(ChatSettingsComponent.valuesFor(SECRETARY, ChatAgent.id(DEFAULT_CHAT), CURRENT, SUBMITTED))
                .isEqualTo(new Values("claude-default", "You are the user's secretary."));
    }

    @Test
    void theChatsOwnAgentShowsWhatTheChatRunsOnIncludingItsOverrides() {
        assertThat(ChatSettingsComponent.valuesFor(DEFAULT_CHAT, ChatAgent.id(DEFAULT_CHAT), CURRENT, SUBMITTED))
                .isEqualTo(CURRENT);
    }

    @Test
    void noAgentKeepsWhatTheFieldsHold() {
        assertThat(ChatSettingsComponent.valuesFor(null, ChatAgent.id(DEFAULT_CHAT), CURRENT, SUBMITTED)).isEqualTo(SUBMITTED);
    }

    @Test
    void theAgentPickerRedrawsTheFormOnChange() throws Exception {
        SessionId session = SessionId.of("s-1");
        String out = new ObjectMapper().findAndRegisterModules().writeValueAsString(
                new ChatSettingsComponent(session, List.of(), List.of(DEFAULT_CHAT, SECRETARY),
                        "agent-default", ChatAgent.id(DEFAULT_CHAT), "p").render());

        assertThat(out).contains("\"url\":\"/chat/api/sessions/s-1/settings/agent\"");
    }

    @Test
    void anAgentOfNoIdIsNeverTheCurrentOne() {
        assertThat(ChatSettingsComponent.valuesFor(SECRETARY, null, CURRENT, SUBMITTED).systemPrompt())
                .isEqualTo("You are the user's secretary.");
        assertThat(AgentId.random()).isNotEqualTo(ChatAgent.id(SECRETARY));
    }
}
