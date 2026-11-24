package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionDirectories;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** A history row says the most urgent thing about its chat in place of its age. */
class ChatShellComponentBadgeTest {

    /** A chat as the management API lists it. */
    private static ManagedSession chat(SessionId id, String title, Instant startedAt) {
        return new ManagedSession(id,
                ai.mindconnect.agent.UserId.of("ada"), null, null, title, ManagedSession.CHAT,
                ManagedSession.Status.ACTIVE, SessionDirectories.NONE, startedAt, java.util.Map.of());
    }

    private static SessionId id(ManagedSession chat) {
        return chat.id();
    }

    private final ManagedSession chat = chat(SessionId.random(), null, Instant.now().minusSeconds(3 * 3600));
    private final Set<SessionId> it = Set.of(id(chat));
    private final Set<SessionId> none = Set.of();

    @Test
    void waitingBeatsRunningBeatsNewBeatsAge() {
        assertThat(ChatShellComponent.badge(chat, it, it, it)).isEqualTo(ChatShellComponent.BADGE_NEEDS_INPUT);
        assertThat(ChatShellComponent.badge(chat, none, it, it)).isEqualTo(ChatShellComponent.BADGE_RUNNING);
        assertThat(ChatShellComponent.badge(chat, none, none, it)).isEqualTo(ChatShellComponent.BADGE_NEW);
        assertThat(ChatShellComponent.badge(chat, none, none, none)).isEqualTo("3h");
    }
}
