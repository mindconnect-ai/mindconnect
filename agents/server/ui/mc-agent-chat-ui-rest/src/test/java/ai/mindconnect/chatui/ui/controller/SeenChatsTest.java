package ai.mindconnect.chatui.ui.controller;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.SessionStatus;
import ai.mindconnect.agent.runtime.domain.view.AgentSessionHeader;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A chat started elsewhere is new to this browser until it has been on screen here. */
class SeenChatsTest {

    private record Chat(SessionId id, Instant startedAt) implements AgentSessionHeader {
        public AgentId agentDefinitionId() { return null; }
        public UserId userId() { return null; }
        public ConversationId conversationId() { return null; }
        public String title() { return null; }
        public SessionStatus status() { return null; }
        public Instant completedAt() { return null; }
        public SessionId parentSessionId() { return null; }
    }

    private static final Instant BEGAN = Instant.parse("2026-09-11T10:00:00Z");
    private static final Chat BEFORE = new Chat(SessionId.random(), BEGAN.minusSeconds(3600));
    private static final Chat ELSEWHERE = new Chat(SessionId.random(), BEGAN.plusSeconds(60));
    private static final Chat HERE = new Chat(SessionId.random(), BEGAN.plusSeconds(30));

    @Test
    void aChatStartedElsewhereIsNewUntilItHasBeenOnScreen() {
        SeenChats seen = SeenChats.from(BEGAN).withShown(HERE.id());
        List<Chat> chats = List.of(ELSEWHERE, HERE, BEFORE);

        assertThat(seen.unseen(chats)).containsExactly(ELSEWHERE.id());
        assertThat(seen.withShown(ELSEWHERE.id()).unseen(chats)).isEmpty();
    }

    @Test
    void theChatsFromBeforeThisBrowserBeganAreNotNewToIt() {
        assertThat(SeenChats.from(BEGAN).unseen(List.of(BEFORE)))
                .as("a first visit does not mark the whole history").isEmpty();
        assertThat(SeenChats.from(BEGAN).unseen(List.of(new Chat(SessionId.random(), null)))).isEmpty();
    }

    @Test
    void recordingAChatLeavesTheStoredValueAlone() {
        SeenChats stored = SeenChats.from(BEGAN);
        SeenChats after = stored.withShown(ELSEWHERE.id());

        assertThat(stored.shown()).isEmpty();
        assertThat(after.shown()).containsExactly(ELSEWHERE.id().value());
        assertThat(after.withShown(ELSEWHERE.id())).as("showing it twice changes nothing").isSameAs(after);
        assertThat(after.since()).isEqualTo(BEGAN);
    }
}
