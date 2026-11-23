package ai.mindconnect.chatui.ui.controller;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionDirectories;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** Coming back to the chat lands where the user was, not on whatever was started last. */
class ChatLandingTest {

    /** A chat as the management API lists it. */
    private static ManagedSession chat(SessionId id, String title, Instant startedAt) {
        return new ManagedSession(ai.mindconnect.agent.runtime.api.domain.SessionId.of(id.value()),
                ai.mindconnect.agent.runtime.api.domain.UserId.of("ada"), null, null, title, ManagedSession.CHAT,
                ManagedSession.Status.ACTIVE, SessionDirectories.NONE, startedAt, java.util.Map.of());
    }

    private static SessionId id(ManagedSession chat) {
        return SessionId.of(chat.id().value());
    }

    private static final ManagedSession STARTED_LAST = chat(SessionId.random(), "A chat", null);
    private static final ManagedSession WORKED_IN = chat(SessionId.random(), "A chat", null);
    private static final List<ManagedSession> NEWEST_FIRST = List.of(STARTED_LAST, WORKED_IN);
    private static final Predicate<ManagedSession> NOTHING_WRITTEN = chat -> false;

    @Test
    void theChatLastOnScreenWins_evenWhenAnotherWasStartedSince() {
        assertThat(ChatLanding.pick(NEWEST_FIRST, id(WORKED_IN), NOTHING_WRITTEN)).contains(id(WORKED_IN));
    }

    @Test
    void withoutOneOnRecordTheMostRecentlyStartedOpens() {
        assertThat(ChatLanding.pick(NEWEST_FIRST, null, NOTHING_WRITTEN)).contains(id(STARTED_LAST));
    }

    @Test
    void aChatThatIsGoneNoLongerCounts() {
        assertThat(ChatLanding.pick(NEWEST_FIRST, SessionId.random(), NOTHING_WRITTEN))
                .as("deleted in another tab, or someone else's").contains(id(STARTED_LAST));
        assertThat(ChatLanding.pick(List.of(), id(WORKED_IN), NOTHING_WRITTEN)).isEmpty();
    }

    @Test
    void anEmptyChatIsPassedOverOnAFreshSignIn() {
        ManagedSession opened = chat(SessionId.random(), null, null);
        ManagedSession written = chat(SessionId.random(), null, null);
        List<ManagedSession> newestFirst = List.of(opened, written, WORKED_IN);
        assertThat(ChatLanding.pick(newestFirst, null, NOTHING_WRITTEN))
                .as("untitled and empty: looked past").contains(id(WORKED_IN));
        assertThat(ChatLanding.pick(newestFirst, null, chat -> chat == written))
                .as("untitled, but written in — the title may have failed").contains(id(written));
        assertThat(ChatLanding.pick(newestFirst, id(opened), NOTHING_WRITTEN))
                .as("the chat on screen stays, empty or not").contains(id(opened));
        assertThat(ChatLanding.pick(List.of(opened), null, NOTHING_WRITTEN)).isEmpty();
    }
}
