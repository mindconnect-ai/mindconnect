package ai.mindconnect.agent.runtime.engine.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import ai.mindconnect.agent.loop.domain.ToolCalls;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.SessionSpec;
import ai.mindconnect.agent.runtime.api.domain.UserId;
import ai.mindconnect.agent.runtime.api.port.in.SessionEventListener;
import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;

/** {@link SessionService} over a {@link SessionStore}: gives a session its id and its time, keeps nothing itself. */
public final class DefaultSessionService implements SessionService {

    private final SessionStore sessions;
    private final SessionEventHub events;

    /**
     * @param events where session events are heard; the same hub the turn
     *               service is given, which is where they come from
     */
    public DefaultSessionService(SessionStore sessions, SessionEventHub events) {
        this.sessions = sessions;
        this.events = events;
    }

    @Override
    public Session open(SessionSpec spec) {
        Session session = new Session(newId(), spec.userId(), spec.parentId(),
                spec.defaultAgent(), spec.modelSettings(), spec.metadata(), Instant.now());
        sessions.save(session);
        return session;
    }

    @Override
    public Optional<Session> find(SessionId sessionId) {
        return sessions.find(sessionId);
    }

    @Override
    public Session fork(SessionId sessionId) {
        Session source = existing(sessionId);
        return copyOf(source, source.parentId());
    }

    @Override
    public Session forkAsChild(SessionId sessionId) {
        return copyOf(existing(sessionId), sessionId);
    }

    @Override
    public Subscription subscribe(UserId userId, SessionEventListener listener) {
        return events.subscribe(userId, listener);
    }

    @Override
    public Subscription subscribe(SessionId sessionId, boolean includeChildSessions, SessionEventListener listener) {
        return events.subscribe(sessionId, includeChildSessions, listener);
    }

    private Session existing(SessionId sessionId) {
        return sessions.find(sessionId).orElseThrow(() ->
                new IllegalArgumentException("There is no session " + sessionId));
    }

    /**
     * The session first, then its messages in one append: a copy that was
     * cut off is a session with nothing in it, never one with half a
     * conversation. The messages keep the turn they were written in — they
     * are the same messages, in another session.
     */
    private Session copyOf(Session source, SessionId parentId) {
        Session copy = new Session(newId(), source.userId(), parentId, source.defaultAgent(),
                source.modelSettings(), source.metadata(), Instant.now());
        sessions.save(copy);
        List<TurnMessage> said = wholeTurns(sessions.messages(source.id()));
        if (!said.isEmpty()) {
            sessions.append(copy.id(), said);
        }
        return copy;
    }

    /** The messages without a last turn that has a tool call open: no model accepts a history that ends there. */
    private static List<TurnMessage> wholeTurns(List<TurnMessage> messages) {
        List<TurnMessage> lastTurn = ToolCalls.episode(messages);
        return ToolCalls.of(lastTurn).allDone()
                ? messages
                : messages.subList(0, messages.size() - lastTurn.size());
    }

    private static SessionId newId() {
        return SessionId.of(UUID.randomUUID().toString());
    }
}
