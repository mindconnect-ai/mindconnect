package ai.mindconnect.agent.management;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionLifecycleEvent;
import ai.mindconnect.agent.management.api.SessionLifecycleListener;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.StreamEvent;
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
            case StreamEvent.AskingLlm asking -> status(event, ManagedSession.Status.BUSY);
            case StreamEvent.Waiting waiting -> status(event, ManagedSession.Status.WAITING);
            case StreamEvent.Done done -> ended(event);
            case StreamEvent.Error error -> ended(event);
            case StreamEvent.Other other when TITLE_FOUND.equals(event.kind()) ->
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
        topOf(event.sessionId()).ifPresent(session -> tell(session.userId(),
                new SessionLifecycleEvent.StatusChanged(session.id(), status)));
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
