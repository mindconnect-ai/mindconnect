package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionDirectories;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiTrigger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The drawer leads where the page says: the chat's own URLs, or a feature's. */
class ChatShellComponentHistoryTest {

    /** A chat as the management API lists it. */
    private static ManagedSession chat(SessionId id, String title, Instant startedAt) {
        return new ManagedSession(id,
                ai.mindconnect.agent.UserId.of("ada"), null, null, title, ManagedSession.CHAT,
                ManagedSession.Status.IDLE, SessionDirectories.NONE, startedAt, java.util.Map.of());
    }

    private static SessionId id(ManagedSession chat) {
        return chat.id();
    }

    private final ManagedSession chat = chat(SessionId.of("s1"), "a talk", Instant.now());

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
