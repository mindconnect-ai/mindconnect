package ai.mindconnect.chatui.ui.controller;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionDirectories;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A chat started elsewhere is new to this browser until it has been on screen here. */
class SeenChatsTest {

    /** A chat as the management API lists it. */
    private static ManagedSession chat(SessionId id, String title, Instant startedAt) {
        return new ManagedSession(id,
                ai.mindconnect.agent.UserId.of("ada"), null, null, title, ManagedSession.CHAT,
                ManagedSession.Status.IDLE, SessionDirectories.NONE, startedAt, java.util.Map.of());
    }

    private static SessionId id(ManagedSession chat) {
        return chat.id();
    }

    private static final Instant BEGAN = Instant.parse("2026-09-11T10:00:00Z");
    private static final ManagedSession BEFORE = chat(SessionId.random(), null, BEGAN.minusSeconds(3600));
    private static final ManagedSession ELSEWHERE = chat(SessionId.random(), null, BEGAN.plusSeconds(60));
    private static final ManagedSession HERE = chat(SessionId.random(), null, BEGAN.plusSeconds(30));

    @Test
    void aChatStartedElsewhereIsNewUntilItHasBeenOnScreen() {
        SeenChats seen = SeenChats.from(BEGAN).withShown(id(HERE));
        List<ManagedSession> chats = List.of(ELSEWHERE, HERE, BEFORE);

        assertThat(seen.unseen(chats)).containsExactly(id(ELSEWHERE));
        assertThat(seen.withShown(id(ELSEWHERE)).unseen(chats)).isEmpty();
    }

    @Test
    void theChatsFromBeforeThisBrowserBeganAreNotNewToIt() {
        assertThat(SeenChats.from(BEGAN).unseen(List.of(BEFORE)))
                .as("a first visit does not mark the whole history").isEmpty();
        assertThat(SeenChats.from(BEGAN).unseen(List.of(chat(SessionId.random(), null, null)))).isEmpty();
    }

    @Test
    void recordingAChatLeavesTheStoredValueAlone() {
        SeenChats stored = SeenChats.from(BEGAN);
        SeenChats after = stored.withShown(id(ELSEWHERE));

        assertThat(stored.shown()).isEmpty();
        assertThat(after.shown()).containsExactly(id(ELSEWHERE).value());
        assertThat(after.withShown(id(ELSEWHERE))).as("showing it twice changes nothing").isSameAs(after);
        assertThat(after.since()).isEqualTo(BEGAN);
    }
}
