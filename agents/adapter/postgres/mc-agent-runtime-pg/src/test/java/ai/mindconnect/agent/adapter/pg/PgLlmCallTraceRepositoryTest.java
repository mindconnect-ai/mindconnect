package ai.mindconnect.agent.adapter.pg;

import ai.mindconnect.agent.domain.LlmCallTrace;
import ai.mindconnect.agent.domain.TraceContext;
import ai.mindconnect.agent.domain.view.LlmCallTraceHeader;
import ai.mindconnect.jdbc.Sql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PgLlmCallTraceRepositoryTest {

    private final UUID conversation = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private Sql sql;
    private PgLlmCallTraceRepository repo;

    @BeforeEach
    void setUp() {
        sql = TestDb.fresh("mc_llm_call_trace");
        repo = new PgLlmCallTraceRepository(sql).initSchema();
    }

    private LlmCallTrace trace(UUID turn, UUID parentTurn, long atMillis) {
        return new LlmCallTrace(UUID.randomUUID(),
                new TraceContext(conversation, session, turn, parentTurn, parentTurn == null ? 0 : 1, "agent"),
                Instant.ofEpochMilli(atMillis), 12L, "agent-default", "claude-sonnet-5", 100, 20, "stop",
                "{\"messages\":[]}", List.of("text"), null, null, null);
    }

    @Test
    void aTraceSurvivesTheRoundTripAndIsFoundEveryWay() {
        UUID turn = UUID.randomUUID();
        LlmCallTrace t = trace(turn, null, 1_000);
        repo.save(t);

        assertThat(repo.findById(t.id())).contains(t);
        assertThat(repo.findByTurn(turn)).containsExactly(t);
        assertThat(repo.findBySession(session)).containsExactly(t);
        assertThat(repo.findByConversation(conversation)).containsExactly(t);
    }

    @Test
    void descendantsAreTheWholeSubtreeInStartOrder() {
        UUID root = UUID.randomUUID();
        UUID childTurn = UUID.randomUUID();
        UUID grandchildTurn = UUID.randomUUID();
        LlmCallTrace rootCall = trace(root, null, 1_000);
        LlmCallTrace child = trace(childTurn, root, 3_000);
        LlmCallTrace grandchild = trace(grandchildTurn, childTurn, 2_000);
        LlmCallTrace unrelated = trace(UUID.randomUUID(), UUID.randomUUID(), 500);
        for (LlmCallTrace t : List.of(rootCall, child, grandchild, unrelated)) repo.save(t);

        assertThat(repo.findDescendants(root)).containsExactly(grandchild, child);
        assertThat(repo.findDescendants(grandchildTurn)).isEmpty();
    }

    @Test
    void headersCarryEverythingButThePayloads() {
        LlmCallTrace t = trace(UUID.randomUUID(), null, 1_000);
        repo.save(t);

        var headers = repo.findHeadersByConversation(conversation);
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
    void aConversationKeepsOnlyItsNewestTraces() {
        PgLlmCallTraceRepository small = new PgLlmCallTraceRepository(sql, 3);
        UUID turn = UUID.randomUUID();
        IntStream.rangeClosed(1, 5).forEach(i -> small.save(trace(turn, null, i * 1_000L)));

        assertThat(small.findByConversation(conversation))
                .extracting(t -> t.startedAt().toEpochMilli()).containsExactly(3_000L, 4_000L, 5_000L);
    }

    @Test
    void deleteBySessionLeavesOtherSessionsAlone() {
        LlmCallTrace mine = trace(UUID.randomUUID(), null, 1_000);
        repo.save(mine);
        UUID otherSession = UUID.randomUUID();
        LlmCallTrace theirs = new LlmCallTrace(UUID.randomUUID(),
                new TraceContext(conversation, otherSession, UUID.randomUUID(), null, 0, "agent"),
                Instant.ofEpochMilli(2_000), 1L, "c", "m", 1, 1, "stop", "{}", List.of(), null, null, null);
        repo.save(theirs);

        repo.deleteBySession(session);
        assertThat(repo.findByConversation(conversation)).containsExactly(theirs);
    }
}
