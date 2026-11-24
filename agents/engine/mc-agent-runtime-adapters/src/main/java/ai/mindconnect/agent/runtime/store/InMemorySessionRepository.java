package ai.mindconnect.agent.runtime.store;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.runtime.store.SessionRepository;

/** Sessions that live as long as the process: for a library user who keeps nothing, and for tests. */
public final class InMemorySessionRepository implements SessionRepository {

    /** A session id means something only within its namespace. */
    private record Key(String namespace, SessionId sessionId) { }

    private final Map<Key, Session> sessions = new ConcurrentHashMap<>();
    private final ScopeSupplier scope;

    public InMemorySessionRepository(ScopeSupplier scope) {
        this.scope = scope;
    }

    private Key key(SessionId sessionId) {
        return new Key(scope.namespace().value(), sessionId);
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

    @Override
    public List<Session> findByUser(UserId user) {
        return inThisNamespace()
                .filter(session -> session.userId().equals(user) && session.parentId() == null)
                .sorted(Comparator.comparing(Session::createdAt).reversed())
                .toList();
    }

    @Override
    public List<Session> findByParent(SessionId parent) {
        return inThisNamespace()
                .filter(session -> parent.equals(session.parentId()))
                .sorted(Comparator.comparing(Session::createdAt))
                .toList();
    }

    @Override
    public boolean delete(SessionId id) {
        return sessions.remove(key(id)) != null;
    }

    private Stream<Session> inThisNamespace() {
        String namespace = scope.namespace().value();
        return sessions.entrySet().stream()
                .filter(entry -> entry.getKey().namespace().equals(namespace))
                .map(Map.Entry::getValue);
    }
}
