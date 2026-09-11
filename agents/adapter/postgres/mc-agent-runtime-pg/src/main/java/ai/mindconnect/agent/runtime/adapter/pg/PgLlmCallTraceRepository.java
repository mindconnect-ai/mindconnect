package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.EntityId;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.LlmCallTrace;
import ai.mindconnect.agent.runtime.domain.TraceContext;
import ai.mindconnect.agent.runtime.domain.TraceId;
import ai.mindconnect.agent.runtime.domain.view.LlmCallTraceHeader;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Row;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.message.domain.ConversationId;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@link LlmCallTraceRepository} on Postgres: one row of {@code mc_llm_call_trace}
 * per LLM call, keyed by {@code (namespace, id)}, with the four ids a trace is
 * looked up by and the start time everything is ordered by. The repository is
 * bound to one namespace and every statement matches it.
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
    private final Namespace namespace;

    public PgLlmCallTraceRepository(DataSource dataSource, Namespace namespace) {
        this(Sql.of(dataSource), DEFAULT_MAX_PER_CONVERSATION, namespace);
    }

    public PgLlmCallTraceRepository(Sql sql, Namespace namespace) {
        this(sql, DEFAULT_MAX_PER_CONVERSATION, namespace);
    }

    public PgLlmCallTraceRepository(Sql sql, int maxTracesPerConversation, Namespace namespace) {
        this.sql = sql;
        this.maxTracesPerConversation = maxTracesPerConversation;
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.traces = DocumentTable.of(LlmCallTrace.class)
                .table("mc_llm_call_trace")
                .partitionKey("namespace", "TEXT", t -> namespace.value())
                .id("id", "TEXT", t -> t.id().value())
                .column("conversation_id", "TEXT", t -> value(t.context().conversationId()))
                .column("session_id", "TEXT", t -> value(t.context().sessionId()))
                .column("turn_id", "TEXT", t -> value(t.context().turnId()))
                .column("parent_turn_id", "TEXT", t -> value(t.context().parentTurnId()))
                .column("started_at", "TIMESTAMPTZ", LlmCallTrace::startedAt)
                .column("depth", "INTEGER", t -> t.context().depth())
                .column("agent_name", "TEXT", t -> t.context().agentName())
                .column("duration_ms", "BIGINT", LlmCallTrace::durationMs)
                .column("llm_config_name", "TEXT", LlmCallTrace::llmConfigName)
                .column("model_name", "TEXT", LlmCallTrace::modelName)
                .column("prompt_tokens", "INTEGER", LlmCallTrace::promptTokens)
                .column("completion_tokens", "INTEGER", LlmCallTrace::completionTokens)
                .column("finish_reason", "TEXT", LlmCallTrace::finishReason)
                .column("error_status", "INTEGER", LlmCallTrace::errorStatus)
                .index("namespace", "conversation_id", "started_at")
                .index("namespace", "session_id")
                .index("namespace", "turn_id")
                .index("namespace", "parent_turn_id")
                .build(sql);
    }

    private static String value(EntityId id) {
        return id == null ? null : id.value();
    }

    public PgLlmCallTraceRepository initSchema() {
        traces.createSchema();
        return this;
    }

    @Override
    public void save(LlmCallTrace trace) {
        traces.save(trace);
        ConversationId conversationId = trace.context().conversationId();
        if (maxTracesPerConversation > 0 && conversationId != null) {
            sql.update("DELETE FROM mc_llm_call_trace WHERE namespace = ? AND id IN ("
                            + "SELECT id FROM mc_llm_call_trace WHERE namespace = ? AND conversation_id = ? "
                            + "ORDER BY started_at DESC NULLS LAST, id DESC OFFSET ?)",
                    namespace.value(), namespace.value(), conversationId.value(), maxTracesPerConversation);
        }
    }

    @Override
    public List<LlmCallTrace> findByTurn(ChatTurnId turn) {
        return traces.find("WHERE namespace = ? AND turn_id = ? " + ORDER, namespace.value(), turn.value());
    }

    @Override
    public List<LlmCallTrace> findBySession(SessionId session) {
        return traces.find("WHERE namespace = ? AND session_id = ? " + ORDER, namespace.value(), session.value());
    }

    @Override
    public List<LlmCallTrace> findByConversation(ConversationId conversation) {
        return traces.find("WHERE namespace = ? AND conversation_id = ? " + ORDER,
                namespace.value(), conversation.value());
    }

    /** Headers from the columns alone — no {@code requestJson}, no {@code responseEvents} leave the database. */
    @Override
    public List<Header> findHeadersByConversation(ConversationId conversation) {
        return traces.select(PgLlmCallTraceRepository::header, "WHERE namespace = ? AND conversation_id = ? " + ORDER,
                namespace.value(), conversation.value());
    }

    /** {@link LlmCallTraceHeader} built from the row. */
    public record Header(TraceId id, TraceContext context, Instant startedAt, long durationMs,
                         String llmConfigName, String modelName, int promptTokens, int completionTokens,
                         String finishReason, Integer errorStatus) implements LlmCallTraceHeader { }

    private static Header header(Row row) throws SQLException {
        TraceContext context = new TraceContext(
                id(row.string("conversation_id"), ConversationId::of),
                id(row.string("session_id"), SessionId::of),
                id(row.string("turn_id"), ChatTurnId::of),
                id(row.string("parent_turn_id"), ChatTurnId::of),
                orZero(row.integer("depth")), row.string("agent_name"));
        Long duration = row.longValue("duration_ms");
        return new Header(TraceId.of(row.string("id")), context, row.instant("started_at"),
                duration == null ? 0L : duration,
                row.string("llm_config_name"), row.string("model_name"), orZero(row.integer("prompt_tokens")),
                orZero(row.integer("completion_tokens")), row.string("finish_reason"), row.integer("error_status"));
    }

    private static <I> I id(String value, Function<String, I> of) {
        return value == null ? null : of.apply(value);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    @Override
    public List<LlmCallTrace> findDescendants(ChatTurnId root) {
        return sql.query("""
                WITH RECURSIVE tree AS (
                    SELECT id, turn_id, started_at, doc FROM mc_llm_call_trace
                    WHERE namespace = ? AND parent_turn_id = ?
                    UNION
                    SELECT t.id, t.turn_id, t.started_at, t.doc
                    FROM mc_llm_call_trace t JOIN tree ON t.parent_turn_id = tree.turn_id
                    WHERE t.namespace = ?
                )
                SELECT doc FROM tree ORDER BY started_at NULLS LAST, id
                """, traces.mapper(), namespace.value(), root.value(), namespace.value());
    }

    @Override
    public Optional<LlmCallTrace> findById(TraceId id) {
        return traces.findById(namespace.value(), id.value());
    }

    @Override
    public void deleteBySession(SessionId session) {
        traces.delete("WHERE namespace = ? AND session_id = ?", namespace.value(), session.value());
    }

    private static final String ORDER = "ORDER BY started_at NULLS LAST, id";
}
