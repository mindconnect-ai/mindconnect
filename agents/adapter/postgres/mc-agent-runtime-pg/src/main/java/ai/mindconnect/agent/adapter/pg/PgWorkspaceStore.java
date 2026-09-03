package ai.mindconnect.agent.adapter.pg;

import ai.mindconnect.agent.tools.workspace.WorkspaceScope;
import ai.mindconnect.agent.tools.workspace.WorkspaceStore;
import ai.mindconnect.jdbc.Sql;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link WorkspaceStore} on Postgres: the files an agent keeps for a user, an
 * agent-user pair or a session, one row of {@code mc_workspace_file} each,
 * keyed by scope and filename. The scope is flattened into one text key —
 * the same three-level layout the file store uses as directories.
 *
 * <p>Text in, text out: content is stored as {@code TEXT}, {@link #readBytes}
 * is its UTF-8 form. As in the file store, {@link #read} strips the content
 * and {@link #readBytes} does not.
 */
public final class PgWorkspaceStore implements WorkspaceStore {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS mc_workspace_file (
                scope      TEXT NOT NULL,
                filename   TEXT NOT NULL,
                user_id    TEXT NOT NULL,
                content    TEXT NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                PRIMARY KEY (scope, filename)
            );
            """;

    private final Sql sql;

    public PgWorkspaceStore(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    public PgWorkspaceStore(Sql sql) {
        this.sql = sql;
    }

    public PgWorkspaceStore initSchema() {
        sql.execute(DDL);
        return this;
    }

    @Override
    public void write(WorkspaceScope scope, String filename, String content) {
        sql.update("INSERT INTO mc_workspace_file (scope, filename, user_id, content) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (scope, filename) DO UPDATE SET content = EXCLUDED.content, updated_at = now()",
                key(scope), filename, scope.userId(), content);
    }

    @Override
    public Optional<String> read(WorkspaceScope scope, String filename) {
        return content(scope, filename).map(String::strip);
    }

    @Override
    public Optional<byte[]> readBytes(WorkspaceScope scope, String filename) {
        return content(scope, filename).map(s -> s.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Optional<Long> sizeOf(WorkspaceScope scope, String filename) {
        return sql.queryOne("SELECT octet_length(content)::bigint AS n FROM mc_workspace_file WHERE scope = ? AND filename = ?",
                row -> row.longValue("n"), key(scope), filename);
    }

    @Override
    public void delete(WorkspaceScope scope, String filename) {
        sql.update("DELETE FROM mc_workspace_file WHERE scope = ? AND filename = ?", key(scope), filename);
    }

    @Override
    public boolean exists(WorkspaceScope scope, String filename) {
        return sql.scalar("SELECT count(*) FROM mc_workspace_file WHERE scope = ? AND filename = ?",
                Long.class, key(scope), filename) > 0;
    }

    @Override
    public List<String> list(WorkspaceScope scope) {
        return sql.query("SELECT filename FROM mc_workspace_file WHERE scope = ? ORDER BY filename",
                row -> row.string("filename"), key(scope));
    }

    /** {@code users/<user>/workspace}, {@code users/<user>/agents/<agent>/workspace}, {@code users/<user>/sessions/<session>/workspace}. */
    static String key(WorkspaceScope scope) {
        String user = "users/" + scope.userId();
        return switch (scope.type()) {
            case USER -> user + "/workspace";
            case AGENT_USER -> user + "/agents/" + id(scope.agentId()) + "/workspace";
            case SESSION -> user + "/sessions/" + id(scope.sessionId()) + "/workspace";
        };
    }

    private static String id(UUID id) {
        if (id == null) throw new IllegalArgumentException("Scope is missing its id");
        return id.toString();
    }

    private Optional<String> content(WorkspaceScope scope, String filename) {
        return sql.queryOne("SELECT content FROM mc_workspace_file WHERE scope = ? AND filename = ?",
                row -> row.string("content"), key(scope), filename);
    }
}
