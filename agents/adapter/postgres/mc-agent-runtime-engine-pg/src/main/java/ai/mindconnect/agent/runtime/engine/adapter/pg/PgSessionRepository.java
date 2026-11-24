package ai.mindconnect.agent.runtime.engine.adapter.pg;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.runtime.store.SessionRepository;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;

/**
 * {@link SessionRepository} on Postgres: one row of {@code mc_session} per
 * session, keyed by {@code (namespace, id)}. The session is the document;
 * beside it stand the columns a listing filters and sorts by — user, parent,
 * time of creation. Every statement matches the namespace of the current
 * call.
 */
public final class PgSessionRepository implements SessionRepository {

    private final DocumentTable<Session> sessions;
    private final ScopeSupplier scope;

    public PgSessionRepository(Sql sql, ScopeSupplier scope) {
        this.scope = scope;
        this.sessions = DocumentTable.of(Session.class)
                .table("mc_session")
                .partitionKey("namespace", "TEXT", session -> scope.namespace().value())
                .id("id", "TEXT", session -> session.id().value())
                .column("user_id", "TEXT", session -> session.userId().value())
                .column("parent_id", "TEXT", session -> session.parentId() == null ? null : session.parentId().value())
                .column("created_at", "TIMESTAMPTZ", Session::createdAt)
                .index("namespace", "user_id", "created_at")
                .index("namespace", "parent_id")
                .build(sql);
    }

    /** Creates the table and its indexes where they are missing. */
    public PgSessionRepository initSchema() {
        sessions.createSchema();
        return this;
    }

    @Override
    public Session create(Session session) {
        if (!sessions.insert(session)) {
            throw new IllegalStateException("Session " + session.id().value() + " exists already");
        }
        return session;
    }

    @Override
    public Optional<Session> find(SessionId id) {
        return sessions.findById(scope.namespace().value(), id.value());
    }

    /** Reads the row {@code FOR UPDATE} and writes the change in the same transaction. */
    @Override
    public Optional<Session> update(SessionId id, UnaryOperator<Session> change) {
        return sessions.update(scope.namespace().value(), id.value(), change);
    }

    @Override
    public List<Session> findByUser(UserId user) {
        return sessions.find("WHERE namespace = ? AND user_id = ? AND parent_id IS NULL "
                + "ORDER BY created_at DESC, id", scope.namespace().value(), user.value());
    }

    @Override
    public List<Session> findByParent(SessionId parent) {
        return sessions.find("WHERE namespace = ? AND parent_id = ? ORDER BY created_at, id",
                scope.namespace().value(), parent.value());
    }

    @Override
    public boolean delete(SessionId id) {
        return sessions.deleteById(scope.namespace().value(), id.value());
    }
}
