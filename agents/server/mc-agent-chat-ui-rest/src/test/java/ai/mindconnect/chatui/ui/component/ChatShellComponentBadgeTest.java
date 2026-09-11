package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.SessionStatus;
import ai.mindconnect.agent.runtime.domain.view.AgentSessionHeader;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** A history row says the most urgent thing about its chat in place of its age. */
class ChatShellComponentBadgeTest {

    private record Chat(SessionId id, Instant startedAt) implements AgentSessionHeader {
        public AgentId agentDefinitionId() { return null; }
        public UserId userId() { return null; }
        public ConversationId conversationId() { return null; }
        public String title() { return null; }
        public SessionStatus status() { return null; }
        public Instant completedAt() { return null; }
        public SessionId parentSessionId() { return null; }
    }

    private final Chat chat = new Chat(SessionId.random(), Instant.now().minusSeconds(3 * 3600));
    private final Set<SessionId> it = Set.of(chat.id());
    private final Set<SessionId> none = Set.of();

    @Test
    void waitingBeatsRunningBeatsNewBeatsAge() {
        assertThat(ChatShellComponent.badge(chat, it, it, it)).isEqualTo(ChatShellComponent.BADGE_NEEDS_INPUT);
        assertThat(ChatShellComponent.badge(chat, none, it, it)).isEqualTo(ChatShellComponent.BADGE_RUNNING);
        assertThat(ChatShellComponent.badge(chat, none, none, it)).isEqualTo(ChatShellComponent.BADGE_NEW);
        assertThat(ChatShellComponent.badge(chat, none, none, none)).isEqualTo("3h");
    }
}
