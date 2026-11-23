package ai.mindconnect.agent.runtime.engine.adapter.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;

/** Sessions and their messages in memory — gone with the process. */
public final class InMemorySessionStore implements SessionStore {

    /** A session id means something only within its namespace. */
    private record Key(String namespace, SessionId sessionId) { }

    private final Map<Key, Session> sessions = new ConcurrentHashMap<>();
    private final Map<Key, List<TurnMessage>> messages = new ConcurrentHashMap<>();
    private final NamespaceProvider namespaces;

    public InMemorySessionStore(NamespaceProvider namespaces) {
        this.namespaces = namespaces;
    }

    private Key key(SessionId sessionId) {
        return new Key(namespaces.current(), sessionId);
    }

    @Override
    public void save(Session session) {
        sessions.put(key(session.id()), session);
    }

    @Override
    public Optional<Session> find(SessionId sessionId) {
        return Optional.ofNullable(sessions.get(key(sessionId)));
    }

    @Override
    public List<TurnMessage> messages(SessionId sessionId) {
        List<TurnMessage> conversation = messages.getOrDefault(key(sessionId), List.of());
        synchronized (conversation) {
            return List.copyOf(conversation);
        }
    }

    @Override
    public List<TurnMessage> append(SessionId sessionId, List<TurnMessage> added) {
        List<TurnMessage> stored = added.stream()
                .map(message -> message.stored(UUID.randomUUID().toString()))
                .toList();
        List<TurnMessage> conversation = messages.computeIfAbsent(key(sessionId), id -> new ArrayList<>());
        synchronized (conversation) {
            conversation.addAll(stored);
        }
        return stored;
    }
}
