package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.SessionStatus;
import ai.mindconnect.agent.runtime.domain.view.AgentSessionHeader;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiTrigger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The drawer leads where the page says: the chat's own URLs, or a feature's. */
class ChatShellComponentHistoryTest {

    private record Chat(SessionId id, Instant startedAt) implements AgentSessionHeader {
        public AgentId agentDefinitionId() { return null; }
        public UserId userId() { return null; }
        public ConversationId conversationId() { return null; }
        public String title() { return "a talk"; }
        public SessionStatus status() { return null; }
        public Instant completedAt() { return null; }
        public SessionId parentSessionId() { return null; }
    }

    private final Chat chat = new Chat(SessionId.of("s1"), Instant.now());

    @Test
    void theChatsOwnDrawerLinksToTheChatRoutes() throws Exception {
        String json = render(new ChatShellComponent(List.of(chat), null, "Chat", UiText.of("c", "x")));
        assertThat(json).contains("\"Chats\"", "\"New chat\"", "/chat/sessions/s1");
    }

    @Test
    void aFeatureHandsInItsOwnLinks() throws Exception {
        var history = new ChatShellComponent.History("Workflow Builder", "New conversation",
                UiTrigger.api("POST", "/admin/builder/workflows/start"),
                id -> "/admin/builder/workflows?session=" + id.value());
        String json = render(new ChatShellComponent(List.of(chat), null, "Chat", UiText.of("c", "x"))
                .withHistory(history));
        assertThat(json)
                .contains("\"Workflow Builder\"", "\"New conversation\"",
                        "/admin/builder/workflows/start", "/admin/builder/workflows?session=s1")
                .doesNotContain("/chat/sessions/s1", "\"New chat\"");
    }

    private static String render(ChatShellComponent shell) throws Exception {
        return new ObjectMapper().writeValueAsString(shell.render());
    }
}
