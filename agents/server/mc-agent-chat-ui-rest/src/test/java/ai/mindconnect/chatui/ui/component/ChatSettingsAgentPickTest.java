package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
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

    private static final AgentDefinition DEFAULT_CHAT =
            AgentDefinition.create("default-chat", "d", "You are a skilled assistant.", null, "agent-default");
    private static final AgentDefinition SECRETARY =
            AgentDefinition.create("secretary", "d", "You are the user's secretary.", null, "claude-default");

    private static final Values CURRENT = new Values("agent-default", "You are a skilled assistant. Be brief.");
    private static final Values SUBMITTED = new Values("openai-default", "My own prompt");

    @Test
    void aDifferentAgentBringsItsOwnModelAndPrompt() {
        assertThat(ChatSettingsComponent.valuesFor(SECRETARY, DEFAULT_CHAT.id(), CURRENT, SUBMITTED))
                .isEqualTo(new Values("claude-default", "You are the user's secretary."));
    }

    @Test
    void theChatsOwnAgentShowsWhatTheChatRunsOnIncludingItsOverrides() {
        assertThat(ChatSettingsComponent.valuesFor(DEFAULT_CHAT, DEFAULT_CHAT.id(), CURRENT, SUBMITTED))
                .isEqualTo(CURRENT);
    }

    @Test
    void noAgentKeepsWhatTheFieldsHold() {
        assertThat(ChatSettingsComponent.valuesFor(null, DEFAULT_CHAT.id(), CURRENT, SUBMITTED)).isEqualTo(SUBMITTED);
    }

    @Test
    void theAgentPickerRedrawsTheFormOnChange() throws Exception {
        SessionId session = SessionId.of("s-1");
        String out = new ObjectMapper().findAndRegisterModules().writeValueAsString(
                new ChatSettingsComponent(session, List.of(), List.of(DEFAULT_CHAT, SECRETARY),
                        "agent-default", DEFAULT_CHAT.id(), "p").render());

        assertThat(out).contains("\"url\":\"/chat/api/sessions/s-1/settings/agent\"");
    }

    @Test
    void anAgentOfNoIdIsNeverTheCurrentOne() {
        assertThat(ChatSettingsComponent.valuesFor(SECRETARY, null, CURRENT, SUBMITTED).systemPrompt())
                .isEqualTo("You are the user's secretary.");
        assertThat(AgentId.random()).isNotEqualTo(SECRETARY.id());
    }
}
