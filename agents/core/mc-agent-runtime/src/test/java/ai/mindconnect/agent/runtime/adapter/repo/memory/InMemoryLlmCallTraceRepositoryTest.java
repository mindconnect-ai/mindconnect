package ai.mindconnect.agent.runtime.adapter.repo.memory;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.LlmCallTrace;
import ai.mindconnect.agent.runtime.domain.TraceContext;
import ai.mindconnect.agent.runtime.domain.TraceId;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-memory adapter is a buffer, not an archive: a long-lived embedded
 * runtime must not grow by one verbatim request/response pair per LLM call.
 */
class InMemoryLlmCallTraceRepositoryTest {

    private static final ConversationId CONVERSATION = ConversationId.random();
    private static final ConversationId OTHER = ConversationId.random();
    private static final SessionId SESSION = SessionId.of("s1");

    @Test
    void keepsOnlyTheNewestTracesPerConversation() {
        var repo = new InMemoryLlmCallTraceRepository(3);
        for (int i = 0; i < 5; i++) repo.save(trace(CONVERSATION, "t" + i, Instant.ofEpochSecond(i)));
        repo.save(trace(OTHER, "o1", Instant.ofEpochSecond(0)));

        assertThat(repo.findByConversation(CONVERSATION))
                .extracting(t -> t.context().turnId().value())
                .containsExactlyInAnyOrder("t2", "t3", "t4");
        assertThat(repo.findByConversation(OTHER)).hasSize(1);   // another conversation's cap is its own
    }

    @Test
    void aCapOfZeroKeepsEverything() {
        var repo = new InMemoryLlmCallTraceRepository(0);
        for (int i = 0; i < 60; i++) repo.save(trace(CONVERSATION, "t" + i, Instant.ofEpochSecond(i)));
        assertThat(repo.findByConversation(CONVERSATION)).hasSize(60);
    }

    @Test
    void theDefaultCapMatchesTheFileAdapter() {
        var repo = new InMemoryLlmCallTraceRepository();
        for (int i = 0; i < 60; i++) repo.save(trace(CONVERSATION, "t" + i, Instant.ofEpochSecond(i)));
        assertThat(repo.findByConversation(CONVERSATION))
                .hasSize(InMemoryLlmCallTraceRepository.DEFAULT_MAX_PER_CONVERSATION);
    }

    @Test
    void deleteBySessionForgetsThatSessionOnly() {
        var repo = new InMemoryLlmCallTraceRepository();
        repo.save(trace(CONVERSATION, "t1", Instant.ofEpochSecond(1)));
        repo.save(trace(OTHER, "o1", Instant.ofEpochSecond(1), SessionId.of("s2")));

        repo.deleteBySession(SESSION);

        assertThat(repo.findBySession(SESSION)).isEmpty();
        assertThat(repo.findBySession(SessionId.of("s2"))).hasSize(1);
    }

    private static LlmCallTrace trace(ConversationId conversation, String turn, Instant startedAt) {
        return trace(conversation, turn, startedAt, SESSION);
    }

    private static LlmCallTrace trace(ConversationId conversation, String turn, Instant startedAt, SessionId session) {
        var context = new TraceContext(conversation, session, ChatTurnId.of(turn), null, 0, "agent");
        return new LlmCallTrace(TraceId.random(), context, startedAt, 1, "cfg", "model", 1, 1, "stop",
                "{}", List.of(), null, null, null);
    }
}
