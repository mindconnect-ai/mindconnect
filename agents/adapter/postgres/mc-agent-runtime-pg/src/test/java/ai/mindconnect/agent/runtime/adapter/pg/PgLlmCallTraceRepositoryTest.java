package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.LlmCallTrace;
import ai.mindconnect.agent.runtime.domain.TraceContext;
import ai.mindconnect.agent.runtime.domain.TraceId;
import ai.mindconnect.agent.runtime.domain.view.LlmCallTraceHeader;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PgLlmCallTraceRepositoryTest {

    private static final Namespace NS = new Namespace("test");

    private final ConversationId conversation = ConversationId.random();
    private final SessionId session = SessionId.random();
    private Sql sql;
    private PgLlmCallTraceRepository repo;

    @BeforeEach
    void setUp() {
        sql = TestDb.fresh("mc_llm_call_trace");
        repo = new PgLlmCallTraceRepository(sql, NS).initSchema();
    }

    private LlmCallTrace trace(ChatTurnId turn, ChatTurnId parentTurn, long atMillis) {
        return new LlmCallTrace(TraceId.random(),
                new TraceContext(conversation, session, turn, parentTurn, parentTurn == null ? 0 : 1, "agent"),
                Instant.ofEpochMilli(atMillis), 12L, "agent-default", "claude-sonnet-5", 100, 20, "stop",
                "{\"messages\":[]}", List.of("text"), null, null, null);
    }

    @Test
    void aTraceSurvivesTheRoundTripAndIsFoundEveryWay() {
        ChatTurnId turn = ChatTurnId.random();
        LlmCallTrace t = trace(turn, null, 1_000);
        repo.save(t);

        assertThat(repo.findById(t.id())).contains(t);
        assertThat(repo.findByTurn(turn)).containsExactly(t);
        assertThat(repo.findBySession(session)).containsExactly(t);
        assertThat(repo.findByConversation(conversation)).containsExactly(t);
    }

    @Test
    void descendantsAreTheWholeSubtreeInStartOrder() {
        ChatTurnId root = ChatTurnId.random();
        ChatTurnId childTurn = ChatTurnId.random();
        ChatTurnId grandchildTurn = ChatTurnId.random();
        LlmCallTrace rootCall = trace(root, null, 1_000);
        LlmCallTrace child = trace(childTurn, root, 3_000);
        LlmCallTrace grandchild = trace(grandchildTurn, childTurn, 2_000);
        LlmCallTrace unrelated = trace(ChatTurnId.random(), ChatTurnId.random(), 500);
        for (LlmCallTrace t : List.of(rootCall, child, grandchild, unrelated)) repo.save(t);

        assertThat(repo.findDescendants(root)).containsExactly(grandchild, child);
        assertThat(repo.findDescendants(grandchildTurn)).isEmpty();
    }

    @Test
    void headersCarryEverythingButThePayloads() {
        LlmCallTrace t = trace(ChatTurnId.random(), null, 1_000);
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
        PgLlmCallTraceRepository small = new PgLlmCallTraceRepository(sql, 3, NS);
        ChatTurnId turn = ChatTurnId.random();
        IntStream.rangeClosed(1, 5).forEach(i -> small.save(trace(turn, null, i * 1_000L)));

        assertThat(small.findByConversation(conversation))
                .extracting(t -> t.startedAt().toEpochMilli()).containsExactly(3_000L, 4_000L, 5_000L);
    }

    @Test
    void deleteBySessionLeavesOtherSessionsAlone() {
        LlmCallTrace mine = trace(ChatTurnId.random(), null, 1_000);
        repo.save(mine);
        SessionId otherSession = SessionId.random();
        LlmCallTrace theirs = new LlmCallTrace(TraceId.random(),
                new TraceContext(conversation, otherSession, ChatTurnId.random(), null, 0, "agent"),
                Instant.ofEpochMilli(2_000), 1L, "c", "m", 1, 1, "stop", "{}", List.of(), null, null, null);
        repo.save(theirs);

        repo.deleteBySession(session);
        assertThat(repo.findByConversation(conversation)).containsExactly(theirs);
    }
}
