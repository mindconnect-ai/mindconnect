package ai.mindconnect.agent.trace.adapter.pg;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.trace.domain.LlmCallTrace;
import ai.mindconnect.agent.trace.domain.TraceContext;
import ai.mindconnect.agent.trace.domain.TraceId;
import ai.mindconnect.agent.trace.domain.LlmCallTraceHeader;
import ai.mindconnect.jdbc.Sql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PgLlmCallTraceRepositoryTest {

    private static final Namespace NS = new Namespace("test");

    private final SessionId session = SessionId.random();
    private Sql sql;
    private PgLlmCallTraceRepository repo;

    @BeforeEach
    void setUp() {
        sql = TestDb.fresh("mc_llm_call_trace");
        repo = new PgLlmCallTraceRepository(sql, ScopeSupplier.fixed(NS)).initSchema();
    }

    private LlmCallTrace trace(String turn, String parentTurn, long atMillis) {
        return new LlmCallTrace(TraceId.random(),
                new TraceContext(session, turn, parentTurn, parentTurn == null ? 0 : 1, "agent"),
                Instant.ofEpochMilli(atMillis), 12L, "agent-default", "claude-sonnet-5", 100, 20, "stop",
                "{\"messages\":[]}", List.of("text"), null, null, null);
    }

    @Test
    void aTraceSurvivesTheRoundTripAndIsFoundEveryWay() {
        String turn = turn();
        LlmCallTrace t = trace(turn, null, 1_000);
        repo.save(t);

        assertThat(repo.findById(t.id())).contains(t);
        assertThat(repo.findByTurn(turn)).containsExactly(t);
        assertThat(repo.findBySession(session)).containsExactly(t);
    }

    @Test
    void descendantsAreTheWholeSubtreeInStartOrder() {
        String root = turn();
        String childTurn = turn();
        String grandchildTurn = turn();
        LlmCallTrace rootCall = trace(root, null, 1_000);
        LlmCallTrace child = trace(childTurn, root, 3_000);
        LlmCallTrace grandchild = trace(grandchildTurn, childTurn, 2_000);
        LlmCallTrace unrelated = trace(turn(), turn(), 500);
        for (LlmCallTrace t : List.of(rootCall, child, grandchild, unrelated)) repo.save(t);

        assertThat(repo.findDescendants(root)).containsExactly(grandchild, child);
        assertThat(repo.findDescendants(grandchildTurn)).isEmpty();
    }

    @Test
    void headersCarryEverythingButThePayloads() {
        LlmCallTrace t = trace(turn(), null, 1_000);
        repo.save(t);

        var headers = repo.findHeadersBySession(session);
        assertThat(headers).hasSize(1);
        LlmCallTraceHeader h = headers.get(0);
        assertThat(h).isInstanceOf(PgLlmCallTraceRepository.Header.class);
        assertThat(List.of(h.id(), h.context(), h.startedAt(), h.durationMs(), h.llmConfigName(), h.modelName(),
                h.promptTokens(), h.completionTokens(), h.finishReason()))
                .containsExactly(t.id(), t.context(), t.startedAt(), t.durationMs(), t.llmConfigName(),
                        t.modelName(), t.promptTokens(), t.completionTokens(), t.finishReason());
        assertThat(h.errorStatus()).isNull();
    }

    @Test
    void aSessionKeepsOnlyItsNewestTraces() {
        PgLlmCallTraceRepository small = new PgLlmCallTraceRepository(sql, 3, ScopeSupplier.fixed(NS));
        String turn = turn();
        IntStream.rangeClosed(1, 5).forEach(i -> small.save(trace(turn, null, i * 1_000L)));

        assertThat(small.findBySession(session))
                .extracting(t -> t.startedAt().toEpochMilli()).containsExactly(3_000L, 4_000L, 5_000L);
    }

    @Test
    void deleteBySessionLeavesOtherSessionsAlone() {
        LlmCallTrace mine = trace(turn(), null, 1_000);
        repo.save(mine);
        SessionId otherSession = SessionId.random();
        LlmCallTrace theirs = new LlmCallTrace(TraceId.random(),
                new TraceContext(otherSession, turn(), null, 0, "agent"),
                Instant.ofEpochMilli(2_000), 1L, "c", "m", 1, 1, "stop", "{}", List.of(), null, null, null);
        repo.save(theirs);

        repo.deleteBySession(session);
        assertThat(repo.findBySession(session)).isEmpty();
        assertThat(repo.findBySession(otherSession)).containsExactly(theirs);
    }

    private static String turn() {
        return java.util.UUID.randomUUID().toString();
    }
}
