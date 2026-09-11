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

/** Coming back to the chat lands where the user was, not on whatever was started last. */
class ChatLandingTest {

    private record Chat(SessionId id) implements AgentSessionHeader {
        public AgentId agentDefinitionId() { return null; }
        public UserId userId() { return null; }
        public ConversationId conversationId() { return null; }
        public String title() { return null; }
        public SessionStatus status() { return null; }
        public Instant startedAt() { return null; }
        public Instant completedAt() { return null; }
        public SessionId parentSessionId() { return null; }
    }

    private static final Chat STARTED_LAST = new Chat(SessionId.random());
    private static final Chat WORKED_IN = new Chat(SessionId.random());
    private static final List<Chat> NEWEST_FIRST = List.of(STARTED_LAST, WORKED_IN);

    @Test
    void theChatLastOnScreenWins_evenWhenAnotherWasStartedSince() {
        assertThat(ChatLanding.pick(NEWEST_FIRST, WORKED_IN.id())).contains(WORKED_IN.id());
    }

    @Test
    void withoutOneOnRecordTheMostRecentlyStartedOpens() {
        assertThat(ChatLanding.pick(NEWEST_FIRST, null)).contains(STARTED_LAST.id());
    }

    @Test
    void aChatThatIsGoneNoLongerCounts() {
        assertThat(ChatLanding.pick(NEWEST_FIRST, SessionId.random()))
                .as("deleted in another tab, or someone else's").contains(STARTED_LAST.id());
        assertThat(ChatLanding.pick(List.of(), WORKED_IN.id())).isEmpty();
    }
}
