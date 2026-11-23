package ai.mindconnect.agent.runtime.engine.adapter.store;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.engine.port.out.SessionRepository;

/** Sessions that live as long as the process: for a library user who keeps nothing, and for tests. */
public final class InMemorySessionRepository implements SessionRepository {

    /** A session id means something only within its namespace. */
    private record Key(String namespace, SessionId sessionId) { }

    private final Map<Key, Session> sessions = new ConcurrentHashMap<>();
    private final NamespaceProvider namespaces;

    public InMemorySessionRepository(NamespaceProvider namespaces) {
        this.namespaces = namespaces;
    }

    private Key key(SessionId sessionId) {
        return new Key(namespaces.current(), sessionId);
    }

    @Override
    public Session create(Session session) {
        if (sessions.putIfAbsent(key(session.id()), session) != null) {
            throw new IllegalStateException("Session " + session.id().value() + " exists already");
        }
        return session;
    }

    @Override
    public Optional<Session> find(SessionId id) {
        return Optional.ofNullable(sessions.get(key(id)));
    }

    @Override
    public Optional<Session> update(SessionId id, UnaryOperator<Session> change) {
        return Optional.ofNullable(sessions.computeIfPresent(key(id), (key, session) -> change.apply(session)));
    }
}
