package ai.mindconnect.agent.runtime.engine.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.port.in.SessionEventFilter;
import ai.mindconnect.agent.runtime.api.port.in.SessionEventListener;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.api.port.out.SessionEventPublisher;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;

/**
 * Hands session events to whoever listens — to one session, to a session
 * with everything under it, or to all sessions of a user. The session
 * service subscribes here and the turn workers publish here, which is why
 * it is an object of its own that both are given.
 *
 * <p>A plugin hears every session without subscribing: a
 * {@link SessionEventListener} it contributes is told each event, of whatever
 * user and namespace, in the namespace the event happened in.
 *
 * <p>From now on, not from the beginning: a listener hears what happens
 * after it subscribed. What a session said before is in the session.
 *
 * <p>Events live in this object: a listener in another process hears
 * nothing.
 */
public final class SessionEventHub implements SessionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SessionEventHub.class);

    /** A listener and what it wants to hear. */
    private record Listening(String namespace, SessionEventFilter filter, SessionEventListener listener) { }

    private final SessionStore sessions;
    private final NamespaceProvider namespaces;
    private final Supplier<List<SessionEventListener>> contributed;
    private final List<Listening> listeners = new CopyOnWriteArrayList<>();
    /** For each session, the sessions it is under, nearest first. A session's parent never changes. */
    private final Map<String, List<SessionId>> ancestors = new ConcurrentHashMap<>();

    /**
     * @param sessions    where a session's parent is looked up
     * @param contributed the listeners plugins contributed, which hear every session
     */
    public SessionEventHub(SessionStore sessions, NamespaceProvider namespaces,
                           Supplier<List<SessionEventListener>> contributed) {
        this.sessions = sessions;
        this.namespaces = namespaces;
        this.contributed = contributed;
    }

    Subscription subscribe(SessionEventFilter filter, SessionEventListener listener) {
        // Heard from the subscriber's namespace only: the same session id elsewhere is another session.
        Listening listening = new Listening(namespaces.current(), filter, listener);
        listeners.add(listening);
        return () -> listeners.remove(listening);
    }

    @Override
    public void publish(SessionEvent event) {
        sessions.find(event.sessionId()).ifPresent(session -> publish(session, event));
    }

    void publish(Session session, SessionEvent event) {
        String namespace = namespaces.current();
        contributed.get().forEach(listener -> tell(listener, event));
        for (Listening listening : listeners) {
            // The detail first: most events are fragments, and most listeners do not want them.
            SessionEventFilter filter = listening.filter();
            if (filter.tells(event) && listening.namespace().equals(namespace) && (filter.names(session)
                    || (filter.childSessions() && ancestorsOf(session).contains(filter.sessionId())))) {
                tell(listening.listener(), event);
            }
        }
    }

    private static void tell(SessionEventListener listener, SessionEvent event) {
        try {
            listener.onEvent(event);
        } catch (RuntimeException e) {
            log.warn("Session event listener failed on {}: {}", event.getClass().getSimpleName(), e.toString());
        }
    }

    private List<SessionId> ancestorsOf(Session session) {
        return ancestors.computeIfAbsent(namespaces.current() + "/" + session.id().value(), id -> {
            List<SessionId> above = new ArrayList<>();
            SessionId parentId = session.parentId();
            while (parentId != null) {
                above.add(parentId);
                parentId = sessions.find(parentId).map(Session::parentId).orElse(null);
            }
            return List.copyOf(above);
        });
    }
}
