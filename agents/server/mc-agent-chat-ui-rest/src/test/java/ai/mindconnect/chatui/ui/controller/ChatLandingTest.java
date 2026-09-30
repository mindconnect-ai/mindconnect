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
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** Coming back to the chat lands where the user was, not on whatever was started last. */
class ChatLandingTest {

    private record Chat(SessionId id, String title) implements AgentSessionHeader {
        Chat(SessionId id) {
            this(id, "A chat");
        }

        public AgentId agentDefinitionId() { return null; }
        public UserId userId() { return null; }
        public ConversationId conversationId() { return null; }
        public SessionStatus status() { return null; }
        public Instant startedAt() { return null; }
        public Instant completedAt() { return null; }
        public SessionId parentSessionId() { return null; }
    }

    private static final Chat STARTED_LAST = new Chat(SessionId.random());
    private static final Chat WORKED_IN = new Chat(SessionId.random());
    private static final List<Chat> NEWEST_FIRST = List.of(STARTED_LAST, WORKED_IN);
    private static final Predicate<AgentSessionHeader> NOTHING_WRITTEN = chat -> false;

    @Test
    void theChatLastOnScreenWins_evenWhenAnotherWasStartedSince() {
        assertThat(ChatLanding.pick(NEWEST_FIRST, WORKED_IN.id(), NOTHING_WRITTEN)).contains(WORKED_IN.id());
    }

    @Test
    void withoutOneOnRecordTheMostRecentlyStartedOpens() {
        assertThat(ChatLanding.pick(NEWEST_FIRST, null, NOTHING_WRITTEN)).contains(STARTED_LAST.id());
    }

    @Test
    void aChatThatIsGoneNoLongerCounts() {
        assertThat(ChatLanding.pick(NEWEST_FIRST, SessionId.random(), NOTHING_WRITTEN))
                .as("deleted in another tab, or someone else's").contains(STARTED_LAST.id());
        assertThat(ChatLanding.pick(List.of(), WORKED_IN.id(), NOTHING_WRITTEN)).isEmpty();
    }

    @Test
    void anEmptyChatIsPassedOverOnAFreshSignIn() {
        Chat opened = new Chat(SessionId.random(), null);
        Chat written = new Chat(SessionId.random(), null);
        List<Chat> newestFirst = List.of(opened, written, WORKED_IN);
        assertThat(ChatLanding.pick(newestFirst, null, NOTHING_WRITTEN))
                .as("untitled and empty: looked past").contains(WORKED_IN.id());
        assertThat(ChatLanding.pick(newestFirst, null, chat -> chat == written))
                .as("untitled, but written in — the title may have failed").contains(written.id());
        assertThat(ChatLanding.pick(newestFirst, opened.id(), NOTHING_WRITTEN))
                .as("the chat on screen stays, empty or not").contains(opened.id());
        assertThat(ChatLanding.pick(List.of(opened), null, NOTHING_WRITTEN)).isEmpty();
    }
}
