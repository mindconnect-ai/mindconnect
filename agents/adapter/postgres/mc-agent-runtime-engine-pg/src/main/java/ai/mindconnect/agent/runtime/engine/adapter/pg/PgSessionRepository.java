package ai.mindconnect.agent.runtime.engine.adapter.pg;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.engine.port.out.SessionRepository;
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
    private final NamespaceProvider namespaces;

    public PgSessionRepository(Sql sql, NamespaceProvider namespaces) {
        this.namespaces = namespaces;
        this.sessions = DocumentTable.of(Session.class)
                .table("mc_session")
                .partitionKey("namespace", "TEXT", session -> namespaces.current())
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
        return sessions.findById(namespaces.current(), id.value());
    }

    /** Reads the row {@code FOR UPDATE} and writes the change in the same transaction. */
    @Override
    public Optional<Session> update(SessionId id, UnaryOperator<Session> change) {
        return sessions.update(namespaces.current(), id.value(), change);
    }

    @Override
    public List<Session> findByUser(UserId user) {
        return sessions.find("WHERE namespace = ? AND user_id = ? AND parent_id IS NULL "
                + "ORDER BY created_at DESC, id", namespaces.current(), user.value());
    }

    @Override
    public List<Session> findByParent(SessionId parent) {
        return sessions.find("WHERE namespace = ? AND parent_id = ? ORDER BY created_at, id",
                namespaces.current(), parent.value());
    }

    @Override
    public boolean delete(SessionId id) {
        return sessions.deleteById(namespaces.current(), id.value());
    }
}
