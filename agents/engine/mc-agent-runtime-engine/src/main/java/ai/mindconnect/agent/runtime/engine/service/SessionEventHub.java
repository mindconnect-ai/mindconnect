package ai.mindconnect.agent.runtime.engine.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.UserId;
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
 * <p>From now on, not from the beginning: a listener hears what happens
 * after it subscribed. What a session said before is in the session.
 *
 * <p>Events live in this object: a listener in another process hears
 * nothing.
 */
public final class SessionEventHub implements SessionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SessionEventHub.class);

    /** A listener and which sessions it wants to hear. */
    private record Listening(String namespace, Predicate<Session> wants, SessionEventListener listener) { }

    private final SessionStore sessions;
    private final NamespaceProvider namespaces;
    private final List<Listening> listeners = new CopyOnWriteArrayList<>();
    /** For each session, the sessions it is under, nearest first. A session's parent never changes. */
    private final Map<String, List<SessionId>> ancestors = new ConcurrentHashMap<>();

    /** @param sessions where a session's parent is looked up */
    public SessionEventHub(SessionStore sessions, NamespaceProvider namespaces) {
        this.sessions = sessions;
        this.namespaces = namespaces;
    }

    /** Every session of this user, sub-agent sessions included. */
    Subscription subscribe(UserId userId, SessionEventListener listener) {
        return subscribe(session -> session.userId().equals(userId), listener);
    }

    /** This session, and with {@code includeChildSessions} every session under it, however deep. */
    Subscription subscribe(SessionId sessionId, boolean includeChildSessions, SessionEventListener listener) {
        return subscribe(session -> session.id().equals(sessionId)
                || (includeChildSessions && ancestorsOf(session).contains(sessionId)), listener);
    }

    private Subscription subscribe(Predicate<Session> wants, SessionEventListener listener) {
        // Heard from the subscriber's namespace only: the same session id elsewhere is another session.
        Listening listening = new Listening(namespaces.current(), wants, listener);
        listeners.add(listening);
        return () -> listeners.remove(listening);
    }

    @Override
    public void publish(SessionEvent event) {
        sessions.find(event.sessionId()).ifPresent(session -> publish(session, event));
    }

    void publish(Session session, SessionEvent event) {
        String namespace = namespaces.current();
        for (Listening listening : listeners) {
            if (!listening.namespace().equals(namespace) || !listening.wants().test(session)) {
                continue;
            }
            try {
                listening.listener().onEvent(event);
            } catch (RuntimeException e) {
                log.warn("Session event listener failed on {}: {}", event.getClass().getSimpleName(), e.toString());
            }
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
