package ai.mindconnect.agent.trace.adapter;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.trace.domain.LlmCallTrace;
import ai.mindconnect.agent.trace.domain.TraceContext;
import ai.mindconnect.agent.trace.domain.TraceId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-memory adapter is a buffer, not an archive: a long-lived embedded
 * runtime must not grow by one verbatim request/response pair per LLM call.
 */
class InMemoryLlmCallTraceRepositoryTest {

    private static final SessionId SESSION = SessionId.of("s1");
    private static final SessionId OTHER = SessionId.of("s2");

    @Test
    void keepsOnlyTheNewestTracesPerSession() {
        var repo = new InMemoryLlmCallTraceRepository(3, ScopeSupplier.local());
        for (int i = 0; i < 5; i++) repo.save(trace(SESSION, "t" + i, Instant.ofEpochSecond(i)));
        repo.save(trace(OTHER, "o1", Instant.ofEpochSecond(0)));

        assertThat(repo.findBySession(SESSION))
                .extracting(t -> t.context().turnId())
                .containsExactlyInAnyOrder("t2", "t3", "t4");
        assertThat(repo.findBySession(OTHER)).hasSize(1);   // another session's cap is its own
    }

    @Test
    void aCapOfZeroKeepsEverything() {
        var repo = new InMemoryLlmCallTraceRepository(0, ScopeSupplier.local());
        for (int i = 0; i < 60; i++) repo.save(trace(SESSION, "t" + i, Instant.ofEpochSecond(i)));
        assertThat(repo.findBySession(SESSION)).hasSize(60);
    }

    @Test
    void theDefaultCapMatchesTheFileAdapter() {
        var repo = new InMemoryLlmCallTraceRepository(ScopeSupplier.local());
        for (int i = 0; i < 60; i++) repo.save(trace(SESSION, "t" + i, Instant.ofEpochSecond(i)));
        assertThat(repo.findBySession(SESSION))
                .hasSize(InMemoryLlmCallTraceRepository.DEFAULT_MAX_PER_SESSION);
    }

    @Test
    void deleteBySessionForgetsThatSessionOnly() {
        var repo = new InMemoryLlmCallTraceRepository(ScopeSupplier.local());
        repo.save(trace(SESSION, "t1", Instant.ofEpochSecond(1)));
        repo.save(trace(OTHER, "o1", Instant.ofEpochSecond(1)));

        repo.deleteBySession(SESSION);

        assertThat(repo.findBySession(SESSION)).isEmpty();
        assertThat(repo.findBySession(OTHER)).hasSize(1);
    }

    private static LlmCallTrace trace(SessionId session, String turn, Instant startedAt) {
        var context = new TraceContext(session, turn, null, 0, "agent");
        return new LlmCallTrace(TraceId.random(), context, startedAt, 1, "cfg", "model", 1, 1, "stop",
                "{}", List.of(), null, null, null);
    }
}
