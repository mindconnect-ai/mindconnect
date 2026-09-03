package ai.mindconnect.agent.adapter.pg;

import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.agent.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.common.AuthenticationInfo;
import ai.mindconnect.jdbc.Sql;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link WorkingMemoryRepository} on Postgres. One row of
 * {@code mc_working_memory} per session holds both things the port keeps
 * side by side — the memory document and the summary text — each nullable,
 * because each has its own lifecycle: a summary can exist before the memory
 * is first saved, and {@link #deleteSummary} must leave the memory alone.
 *
 * <p>Keyed by session <em>and</em> user, as the file store's directory
 * layout is: another user's auth never reads or overwrites a session's
 * memory, even with the right session id.
 */
public final class PgWorkingMemoryRepository implements WorkingMemoryRepository {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS mc_working_memory (
                session_id UUID PRIMARY KEY,
                user_id    TEXT NOT NULL,
                memory     JSONB,
                summary    TEXT,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            """;

    private final Sql sql;

    public PgWorkingMemoryRepository(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    public PgWorkingMemoryRepository(Sql sql) {
        this.sql = sql;
    }

    public PgWorkingMemoryRepository initSchema() {
        sql.execute(DDL);
        return this;
    }

    @Override
    public void save(UUID sessionId, AuthenticationInfo auth, WorkingMemory memory) {
        sql.update("INSERT INTO mc_working_memory (session_id, user_id, memory) VALUES (?, ?, ?) "
                        + "ON CONFLICT (session_id) DO UPDATE SET memory = EXCLUDED.memory, updated_at = now() "
                        + "WHERE mc_working_memory.user_id = EXCLUDED.user_id",
                sessionId, auth.userId(), sql.json().jsonb(memory));
    }

    @Override
    public Optional<WorkingMemory> findBySessionId(UUID sessionId, AuthenticationInfo auth) {
        return sql.queryOne("SELECT memory FROM mc_working_memory WHERE session_id = ? AND user_id = ? AND memory IS NOT NULL",
                row -> row.json("memory", WorkingMemory.class), sessionId, auth.userId());
    }

    @Override
    public void delete(UUID sessionId, AuthenticationInfo auth) {
        sql.update("DELETE FROM mc_working_memory WHERE session_id = ? AND user_id = ?", sessionId, auth.userId());
    }

    @Override
    public void saveSummary(UUID sessionId, AuthenticationInfo auth, String summary) {
        sql.update("INSERT INTO mc_working_memory (session_id, user_id, summary) VALUES (?, ?, ?) "
                        + "ON CONFLICT (session_id) DO UPDATE SET summary = EXCLUDED.summary, updated_at = now() "
                        + "WHERE mc_working_memory.user_id = EXCLUDED.user_id",
                sessionId, auth.userId(), summary);
    }

    /** Blank summaries read as absent, as they do from the file store. */
    @Override
    public Optional<String> loadSummary(UUID sessionId, AuthenticationInfo auth) {
        return sql.queryOne("SELECT summary FROM mc_working_memory WHERE session_id = ? AND user_id = ?",
                        row -> row.string("summary"), sessionId, auth.userId())
                .map(String::strip)
                .filter(s -> !s.isBlank());
    }

    @Override
    public void deleteSummary(UUID sessionId, AuthenticationInfo auth) {
        sql.update("UPDATE mc_working_memory SET summary = NULL, updated_at = now() WHERE session_id = ? AND user_id = ?",
                sessionId, auth.userId());
    }
}
