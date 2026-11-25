package ai.mindconnect.agent.migration.pg;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;

import ai.mindconnect.agent.migration.earlier.EarlierRuntimeData;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.jdbc.Sql;

/**
 * The earlier runtime's tables: {@code mc_agent_definition},
 * {@code mc_agent_session}, {@code mc_message} and {@code mc_conversation_summary}, each row's
 * document as it lies. A database the earlier runtime never wrote to has
 * none of them, and nothing is read.
 */
public final class PgEarlierRuntimeData implements EarlierRuntimeData {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };

    private final Sql sql;
    private final ScopeSupplier scope;

    public PgEarlierRuntimeData(Sql sql, ScopeSupplier scope) {
        this.sql = sql;
        this.scope = scope;
    }

    @Override
    public List<String> agents() {
        if (!exists("mc_agent_definition")) {
            return List.of();
        }
        return sql.query("SELECT doc::text AS doc FROM mc_agent_definition WHERE namespace = ? ORDER BY id",
                row -> row.string("doc"), scope.namespace().value());
    }

    @Override
    public List<Map<String, Object>> sessions() {
        if (!exists("mc_agent_session")) {
            return List.of();
        }
        return sql.query("SELECT doc FROM mc_agent_session WHERE namespace = ? ORDER BY started_at NULLS FIRST, id",
                row -> row.json("doc", OBJECT), scope.namespace().value());
    }

    @Override
    public List<Map<String, Object>> messages(String conversationId) {
        if (!exists("mc_message")) {
            return List.of();
        }
        return sql.query("SELECT doc FROM mc_message WHERE namespace = ? AND conversation_id = ? ORDER BY seq, id",
                row -> row.json("doc", OBJECT), scope.namespace().value(), conversationId);
    }

    @Override
    public Optional<Summary> latestSummary(String conversationId) {
        if (!exists("mc_conversation_summary")) {
            return Optional.empty();
        }
        return sql.queryOne("SELECT doc->>'content' AS text, (doc->>'toSequenceNum')::bigint AS through "
                        + "FROM mc_conversation_summary WHERE namespace = ? AND conversation_id = ? "
                        + "AND doc->>'content' IS NOT NULL AND doc->>'toSequenceNum' IS NOT NULL "
                        + "ORDER BY (doc->>'toSequenceNum')::bigint DESC LIMIT 1",
                row -> new Summary(row.string("text"), row.longValue("through")),
                scope.namespace().value(), conversationId);
    }

    private boolean exists(String table) {
        return Boolean.TRUE.equals(sql.scalar("SELECT to_regclass(?) IS NOT NULL", Boolean.class, "public." + table));
    }
}
