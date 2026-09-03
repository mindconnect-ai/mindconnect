package ai.mindconnect.agent.adapter.pg;

import ai.mindconnect.agent.domain.LlmCallTrace;
import ai.mindconnect.agent.domain.TraceContext;
import ai.mindconnect.agent.domain.view.LlmCallTraceHeader;
import ai.mindconnect.agent.port.out.LlmCallTraceRepository;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Row;
import ai.mindconnect.jdbc.Sql;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link LlmCallTraceRepository} on Postgres: one row of {@code mc_llm_call_trace}
 * per LLM call, with the four ids a trace is looked up by and the start time
 * everything is ordered by.
 *
 * <p>Retention is the file store's rule: after each save, a conversation
 * keeps its newest {@code maxTracesPerConversation} traces and the rest are
 * dropped; zero or less keeps everything. (The file store calls the knob
 * "per session" but has always counted per conversation — the name here says
 * what it does.)
 *
 * <p>{@link #findDescendants} walks the parent-turn links in the database
 * with a recursive query, so a deep sub-agent tree costs one round trip; the
 * UNION makes a cycle terminate rather than loop.
 */
public final class PgLlmCallTraceRepository implements LlmCallTraceRepository {

    public static final int DEFAULT_MAX_PER_CONVERSATION = 50;

    private final Sql sql;
    private final DocumentTable<LlmCallTrace> traces;
    private final int maxTracesPerConversation;

    public PgLlmCallTraceRepository(DataSource dataSource) {
        this(Sql.of(dataSource), DEFAULT_MAX_PER_CONVERSATION);
    }

    public PgLlmCallTraceRepository(Sql sql) {
        this(sql, DEFAULT_MAX_PER_CONVERSATION);
    }

    public PgLlmCallTraceRepository(Sql sql, int maxTracesPerConversation) {
        this.sql = sql;
        this.maxTracesPerConversation = maxTracesPerConversation;
        this.traces = DocumentTable.of(LlmCallTrace.class)
                .table("mc_llm_call_trace")
                .id("id", "UUID", LlmCallTrace::id)
                .column("conversation_id", "UUID", t -> t.context().conversationId())
                .column("session_id", "UUID", t -> t.context().sessionId())
                .column("turn_id", "UUID", t -> t.context().turnId())
                .column("parent_turn_id", "UUID", t -> t.context().parentTurnId())
                .column("started_at", "TIMESTAMPTZ", LlmCallTrace::startedAt)
                // The rest of the header, so a trace list never touches the
                // payloads that make a trace document large.
                .column("depth", "INTEGER", t -> t.context().depth())
                .column("agent_name", "TEXT", t -> t.context().agentName())
                .column("duration_ms", "BIGINT", LlmCallTrace::durationMs)
                .column("llm_config_name", "TEXT", LlmCallTrace::llmConfigName)
                .column("model_name", "TEXT", LlmCallTrace::modelName)
                .column("prompt_tokens", "INTEGER", LlmCallTrace::promptTokens)
                .column("completion_tokens", "INTEGER", LlmCallTrace::completionTokens)
                .column("finish_reason", "TEXT", LlmCallTrace::finishReason)
                .column("error_status", "INTEGER", LlmCallTrace::errorStatus)
                .index("conversation_id", "started_at")
                .index("session_id")
                .index("turn_id")
                .index("parent_turn_id")
                .build(sql);
    }

    public PgLlmCallTraceRepository initSchema() {
        traces.createSchema();
        return this;
    }

    @Override
    public void save(LlmCallTrace trace) {
        traces.save(trace);
        UUID conversationId = trace.context().conversationId();
        if (maxTracesPerConversation > 0 && conversationId != null) {
            sql.update("DELETE FROM mc_llm_call_trace WHERE id IN ("
                            + "SELECT id FROM mc_llm_call_trace WHERE conversation_id = ? "
                            + "ORDER BY started_at DESC NULLS LAST, id DESC OFFSET ?)",
                    conversationId, maxTracesPerConversation);
        }
    }

    @Override
    public List<LlmCallTrace> findByTurn(UUID turnId) {
        return traces.find("WHERE turn_id = ? " + ORDER, turnId);
    }

    @Override
    public List<LlmCallTrace> findBySession(UUID sessionId) {
        return traces.find("WHERE session_id = ? " + ORDER, sessionId);
    }

    @Override
    public List<LlmCallTrace> findByConversation(UUID conversationId) {
        return traces.find("WHERE conversation_id = ? " + ORDER, conversationId);
    }

    /** Headers from the columns alone — no {@code requestJson}, no {@code responseEvents} leave the database. */
    @Override
    public List<Header> findHeadersByConversation(UUID conversationId) {
        return traces.select(PgLlmCallTraceRepository::header, "WHERE conversation_id = ? " + ORDER, conversationId);
    }

    /** {@link LlmCallTraceHeader} built from the row. */
    public record Header(UUID id, TraceContext context, Instant startedAt, long durationMs,
                         String llmConfigName, String modelName, int promptTokens, int completionTokens,
                         String finishReason, Integer errorStatus) implements LlmCallTraceHeader { }

    private static Header header(Row row) throws SQLException {
        TraceContext context = new TraceContext(row.uuid("conversation_id"), row.uuid("session_id"),
                row.uuid("turn_id"), row.uuid("parent_turn_id"), orZero(row.integer("depth")), row.string("agent_name"));
        Long duration = row.longValue("duration_ms");
        return new Header(row.uuid("id"), context, row.instant("started_at"), duration == null ? 0L : duration,
                row.string("llm_config_name"), row.string("model_name"), orZero(row.integer("prompt_tokens")),
                orZero(row.integer("completion_tokens")), row.string("finish_reason"), row.integer("error_status"));
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    @Override
    public List<LlmCallTrace> findDescendants(UUID rootTurnId) {
        return sql.query("""
                WITH RECURSIVE tree AS (
                    SELECT id, turn_id, started_at, doc FROM mc_llm_call_trace WHERE parent_turn_id = ?
                    UNION
                    SELECT t.id, t.turn_id, t.started_at, t.doc
                    FROM mc_llm_call_trace t JOIN tree ON t.parent_turn_id = tree.turn_id
                )
                SELECT doc FROM tree ORDER BY started_at NULLS LAST, id
                """, traces.mapper(), rootTurnId);
    }

    @Override
    public Optional<LlmCallTrace> findById(UUID id) {
        return traces.findById(id);
    }

    @Override
    public void deleteBySession(UUID sessionId) {
        traces.delete("WHERE session_id = ?", sessionId);
    }

    private static final String ORDER = "ORDER BY started_at NULLS LAST, id";
}
