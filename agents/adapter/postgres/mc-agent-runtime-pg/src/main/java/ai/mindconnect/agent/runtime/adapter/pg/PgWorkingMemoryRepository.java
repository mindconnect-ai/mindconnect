package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.AuthenticationInfo;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.jdbc.Sql;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link WorkingMemoryRepository} on Postgres. One row of
 * {@code mc_working_memory} per session holds both things the port keeps
 * side by side — the memory document and the summary text — each nullable,
 * because each has its own lifecycle: a summary can exist before the memory
 * is first saved, and {@link #deleteSummary} must leave the memory alone.
 *
 * <p>Keyed by {@code (namespace, session_id)}. The repository is bound to one
 * namespace, and every statement also matches the user, as the file store's
 * directory layout does: another user's auth never reads or overwrites a
 * session's memory, even with the right session id.
 */
public final class PgWorkingMemoryRepository implements WorkingMemoryRepository {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS mc_working_memory (
                namespace  TEXT NOT NULL,
                session_id TEXT NOT NULL,
                user_id    TEXT NOT NULL,
                memory     JSONB,
                summary    TEXT,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                PRIMARY KEY (namespace, session_id)
            );
            """;

    private final Sql sql;
    private final Namespace namespace;

    public PgWorkingMemoryRepository(DataSource dataSource, Namespace namespace) {
        this(Sql.of(dataSource), namespace);
    }

    public PgWorkingMemoryRepository(Sql sql, Namespace namespace) {
        this.sql = sql;
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    public PgWorkingMemoryRepository initSchema() {
        sql.execute(DDL);
        return this;
    }

    @Override
    public void save(SessionId sessionId, AuthenticationInfo auth, WorkingMemory memory) {
        sql.update("INSERT INTO mc_working_memory (namespace, session_id, user_id, memory) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (namespace, session_id) DO UPDATE SET memory = EXCLUDED.memory, updated_at = now() "
                        + "WHERE mc_working_memory.user_id = EXCLUDED.user_id",
                namespace.value(), sessionId.value(), auth.userId().value(), sql.json().jsonb(memory));
    }

    @Override
    public Optional<WorkingMemory> findBySession(SessionId sessionId, AuthenticationInfo auth) {
        return sql.queryOne("SELECT memory FROM mc_working_memory "
                        + "WHERE namespace = ? AND session_id = ? AND user_id = ? AND memory IS NOT NULL",
                row -> row.json("memory", WorkingMemory.class),
                namespace.value(), sessionId.value(), auth.userId().value());
    }

    @Override
    public void delete(SessionId sessionId, AuthenticationInfo auth) {
        sql.update("DELETE FROM mc_working_memory WHERE namespace = ? AND session_id = ? AND user_id = ?",
                namespace.value(), sessionId.value(), auth.userId().value());
    }

    @Override
    public void saveSummary(SessionId sessionId, AuthenticationInfo auth, String summary) {
        sql.update("INSERT INTO mc_working_memory (namespace, session_id, user_id, summary) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (namespace, session_id) DO UPDATE SET summary = EXCLUDED.summary, updated_at = now() "
                        + "WHERE mc_working_memory.user_id = EXCLUDED.user_id",
                namespace.value(), sessionId.value(), auth.userId().value(), summary);
    }

    /** Blank summaries read as absent, as they do from the file store. */
    @Override
    public Optional<String> loadSummary(SessionId sessionId, AuthenticationInfo auth) {
        return sql.queryOne("SELECT summary FROM mc_working_memory WHERE namespace = ? AND session_id = ? AND user_id = ?",
                        row -> row.string("summary"), namespace.value(), sessionId.value(), auth.userId().value())
                .map(String::strip)
                .filter(s -> !s.isBlank());
    }

    @Override
    public void deleteSummary(SessionId sessionId, AuthenticationInfo auth) {
        sql.update("UPDATE mc_working_memory SET summary = NULL, updated_at = now() "
                        + "WHERE namespace = ? AND session_id = ? AND user_id = ?",
                namespace.value(), sessionId.value(), auth.userId().value());
    }
}
