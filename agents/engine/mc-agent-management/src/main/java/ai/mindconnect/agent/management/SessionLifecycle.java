package ai.mindconnect.agent.management;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionLifecycleEvent;
import ai.mindconnect.agent.management.api.SessionLifecycleListener;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.loop.domain.TurnStreamEvent;
import ai.mindconnect.agent.runtime.api.domain.SessionStreamEvent;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.port.in.SessionEventListener;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;
import ai.mindconnect.agent.runtime.store.SessionRepository;
import ai.mindconnect.channel.ChannelRegistry;

/**
 * What goes on in the sessions of an installation, as far as a list of
 * sessions is concerned: which are busy, which wait, which were opened,
 * renamed or deleted. It hears every session's stream — contributed to the
 * runtime as a listener — and tells each user of their own sessions.
 *
 * <p>A session's status is read from its turn, here and nowhere else: when
 * a session is read, and when one of its turns begins, asks or ends.
 *
 * <p>A user's listeners hear from now on, each on a thread of its own, so a
 * slow one costs the turn that reports nothing. What is told lives in this
 * object: a listener in another process hears nothing.
 */
public final class SessionLifecycle implements SessionEventListener {

    /** The kind of the event the title capability tells a found title with; its details name the {@code title}. */
    private static final String TITLE_FOUND = "session.title";
    private static final Duration IDLE = Duration.ofMinutes(10);

    private final SessionRepository sessions;
    private final Function<SessionId, Optional<Turn>> runningTurn;
    private final ChannelRegistry channels = new ChannelRegistry().withIdleEviction(IDLE);
    /** The status last told of each session that is not idle, by its user and id. */
    private final Map<String, ManagedSession.Status> statusTold = new ConcurrentHashMap<>();

    /** @param runningTurn the turn of a session that has not ended, if there is one */
    public SessionLifecycle(SessionRepository sessions, Function<SessionId, Optional<Turn>> runningTurn) {
        this.sessions = sessions;
        this.runningTurn = runningTurn;
    }

    /** What goes on in the session right now, read from its turn. */
    ManagedSession.Status statusOf(SessionId sessionId) {
        return runningTurn.apply(sessionId).map(turn -> switch (turn.status()) {
            case INTERRUPTED -> ManagedSession.Status.WAITING;
            case QUEUED, RUNNING, WAITING -> ManagedSession.Status.BUSY;
            case COMPLETED, INCOMPLETE, CANCELLED, FAILED -> ManagedSession.Status.IDLE;
        }).orElse(ManagedSession.Status.IDLE);
    }

    Subscription subscribe(UserId user, SessionLifecycleListener listener) {
        var channel = channels.<SessionLifecycleEvent>channel(user.value());
        return channel.subscribe(channel.lastSeq(), event -> listener.onEvent(event.value()))::close;
    }

    /** Tells the session's user; nobody, for a session nobody owns. */
    void tell(UserId user, SessionLifecycleEvent event) {
        if (user != null) {
            channels.<SessionLifecycleEvent>channel(user.value()).publish(event);
        }
    }

    @Override
    public void onEvent(SessionEvent event) {
        // What a turn says while it runs is nothing a list shows: only that it begins, asks and ends.
        switch (event.known()) {
            case TurnStreamEvent.AskingLlm asking -> status(event, ManagedSession.Status.BUSY);
            case SessionStreamEvent.Waiting waiting -> status(event, ManagedSession.Status.WAITING);
            case SessionStreamEvent.Resumed resumed -> status(event, ManagedSession.Status.BUSY);
            case SessionStreamEvent.Done done -> ended(event);
            case SessionStreamEvent.Error error -> ended(event);
            case SessionStreamEvent.Other other when TITLE_FOUND.equals(event.kind()) ->
                    sessions.find(event.sessionId()).ifPresent(session -> tell(session.userId(),
                            new SessionLifecycleEvent.Renamed(session.id(),
                                    String.valueOf(event.details().get("title")))));
            default -> { }
        }
    }

    /** The turn that was submitted is over: its session is idle. A sub-agent's end leaves the turn above it running. */
    private void ended(SessionEvent event) {
        if (!event.ofSubAgent()) {
            status(event, ManagedSession.Status.IDLE);
        }
    }

    /** Whichever session of the tree it happened in, it is the session the user sees that changes. */
    private void status(SessionEvent event, ManagedSession.Status status) {
        topOf(event.sessionId()).ifPresent(session -> {
            // Several things say the same of a session — an answer, then the model being asked again:
            // a user is told when the status changes, not each time it is confirmed.
            String told = session.userId() + "/" + session.id().value();
            ManagedSession.Status before = status == ManagedSession.Status.IDLE
                    ? statusTold.remove(told) : statusTold.put(told, status);
            if (before != status) {
                tell(session.userId(), new SessionLifecycleEvent.StatusChanged(session.id(), status));
            }
        });
    }

    /** The session at the top of the tree this one is in. */
    private Optional<Session> topOf(SessionId sessionId) {
        Optional<Session> session = sessions.find(sessionId);
        while (session.isPresent() && session.get().parentId() != null) {
            session = sessions.find(session.get().parentId());
        }
        return session;
    }
}
