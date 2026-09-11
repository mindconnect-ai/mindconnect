package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.EntityId;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.runtime.tools.workspace.WorkspaceScope;
import ai.mindconnect.agent.runtime.tools.workspace.WorkspaceStore;
import ai.mindconnect.jdbc.Sql;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link WorkspaceStore} on Postgres: the files an agent keeps for a user, an
 * agent-user pair or a session, one row of {@code mc_workspace_file} each,
 * keyed by {@code (namespace, scope, filename)}. The scope is flattened into
 * one text key — the same three-level layout the file store uses as
 * directories. The store is bound to one namespace and every statement
 * matches it.
 *
 * <p>Text in, text out: content is stored as {@code TEXT}, {@link #readBytes}
 * is its UTF-8 form. As in the file store, {@link #read} strips the content
 * and {@link #readBytes} does not.
 */
public final class PgWorkspaceStore implements WorkspaceStore {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS mc_workspace_file (
                namespace  TEXT NOT NULL,
                scope      TEXT NOT NULL,
                filename   TEXT NOT NULL,
                user_id    TEXT NOT NULL,
                content    TEXT NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                PRIMARY KEY (namespace, scope, filename)
            );
            """;

    private final Sql sql;
    private final Namespace namespace;

    public PgWorkspaceStore(DataSource dataSource, Namespace namespace) {
        this(Sql.of(dataSource), namespace);
    }

    public PgWorkspaceStore(Sql sql, Namespace namespace) {
        this.sql = sql;
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    public PgWorkspaceStore initSchema() {
        sql.execute(DDL);
        return this;
    }

    @Override
    public void write(WorkspaceScope scope, String filename, String content) {
        sql.update("INSERT INTO mc_workspace_file (namespace, scope, filename, user_id, content) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (namespace, scope, filename) DO UPDATE SET content = EXCLUDED.content, updated_at = now()",
                namespace.value(), key(scope), filename, scope.userId().value(), content);
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
        return sql.queryOne("SELECT octet_length(content)::bigint AS n FROM mc_workspace_file "
                        + "WHERE namespace = ? AND scope = ? AND filename = ?",
                row -> row.longValue("n"), namespace.value(), key(scope), filename);
    }

    @Override
    public void delete(WorkspaceScope scope, String filename) {
        sql.update("DELETE FROM mc_workspace_file WHERE namespace = ? AND scope = ? AND filename = ?",
                namespace.value(), key(scope), filename);
    }

    @Override
    public boolean exists(WorkspaceScope scope, String filename) {
        return sql.scalar("SELECT count(*) FROM mc_workspace_file WHERE namespace = ? AND scope = ? AND filename = ?",
                Long.class, namespace.value(), key(scope), filename) > 0;
    }

    @Override
    public List<String> list(WorkspaceScope scope) {
        return sql.query("SELECT filename FROM mc_workspace_file WHERE namespace = ? AND scope = ? ORDER BY filename",
                row -> row.string("filename"), namespace.value(), key(scope));
    }

    /** {@code users/<user>/workspace}, {@code users/<user>/agents/<agent>/workspace}, {@code users/<user>/sessions/<session>/workspace}. */
    static String key(WorkspaceScope scope) {
        String user = "users/" + scope.userId().value();
        return switch (scope.type()) {
            case USER -> user + "/workspace";
            case AGENT_USER -> user + "/agents/" + id(scope.agentId()) + "/workspace";
            case SESSION -> user + "/sessions/" + id(scope.sessionId()) + "/workspace";
        };
    }

    private static String id(EntityId id) {
        if (id == null) throw new IllegalArgumentException("Scope is missing its id");
        return id.value();
    }

    private Optional<String> content(WorkspaceScope scope, String filename) {
        return sql.queryOne("SELECT content FROM mc_workspace_file WHERE namespace = ? AND scope = ? AND filename = ?",
                row -> row.string("content"), namespace.value(), key(scope), filename);
    }
}
