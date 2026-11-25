package ai.mindconnect.agent.runtime.engine.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.StreamEvent;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.port.in.SessionEventListener;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;
import ai.mindconnect.agent.runtime.api.port.out.SessionEventPublisher;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.scope.Scope;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.ChannelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands the events of sessions to whoever listens, through channels: one for
 * each tree of sessions, a session with every session under it. The turn
 * workers publish here; the session service and the turn service subscribe
 * here — to a session, or to one turn of it.
 *
 * <p>A channel numbers what it is given, keeps the newest of it and gives
 * each listener a queue and a thread of its own, so a slow listener costs
 * the turn nothing: it is the listener that falls behind, and one that falls
 * too far behind loses the oldest of what waited for it. A channel nobody
 * listens to is given up after ten idle minutes.
 *
 * <p>A plugin hears every session without subscribing: a
 * {@link SessionEventListener} it contributes is told each event, of whatever
 * user and namespace, in the namespace the event happened in and before
 * anybody else is.
 *
 * <p>Events live in this object: a listener in another process hears
 * nothing.
 */
public final class SessionEventHub implements SessionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SessionEventHub.class);
    private static final Duration IDLE = Duration.ofMinutes(10);

    /** What travels on a stream channel. */
    private sealed interface Carried { }

    /**
     * An event with what a listener is chosen by: the session it happened
     * in, the sessions above that one, and the turn it belongs to with the
     * tasks that one runs under.
     */
    private record Happened(String namespace, Session session, List<SessionId> sessionsAbove, List<TurnId> turns,
                            SessionEvent event) implements Carried { }

    /** Sent by a listener to itself: when it arrives, everything buffered before it has been heard. */
    private record CaughtUp(Object listener) implements Carried { }

    private final SessionStore sessions;
    private final ScopeSupplier scope;
    private final Supplier<List<SessionEventListener>> contributed;
    private final ChannelRegistry channels = new ChannelRegistry().withIdleEviction(IDLE);
    /** For each session, the sessions it is under, nearest first. A session's parent never changes. */
    private final Map<String, List<SessionId>> ancestors = new ConcurrentHashMap<>();

    /**
     * @param sessions    where a session's parent is looked up
     * @param contributed the listeners plugins contributed, which hear every session
     */
    public SessionEventHub(SessionStore sessions, ScopeSupplier scope,
                           Supplier<List<SessionEventListener>> contributed) {
        this.sessions = sessions;
        this.scope = scope;
        this.contributed = contributed;
    }

    /** Hears one session as it happens, and with {@code childSessions} every session under it, from now on. */
    Subscription stream(SessionId sessionId, boolean childSessions, SessionEventListener listener) {
        Channel<Carried> channel = streamChannel(rootOf(sessionId));
        return channel.subscribe(channel.lastSeq(),
                carried -> carried instanceof Happened happened && isUnder(happened, sessionId, childSessions),
                asSubscriber(event -> listener.onEvent(((Happened) event.value()).event())))::close;
    }

    /**
     * An event with its number on the stream of its session tree.
     *
     * @param submittedTurn the turn that was submitted, under which the event's own turn runs;
     *                      {@code null} for an event of no turn
     */
    public record Numbered(long seq, TurnId submittedTurn, SessionEvent event) { }

    /**
     * Hears a session and every session under it from after {@code afterSeq}:
     * what the stream still keeps is told first, then it goes on as it happens.
     * For a caller that reconnects and names the last number it saw — the
     * numbers are this process's own, which is why the API has none.
     */
    public Subscription replay(SessionId sessionId, long afterSeq, Consumer<Numbered> listener) {
        return streamChannel(rootOf(sessionId)).subscribe(afterSeq,
                carried -> carried instanceof Happened happened && isUnder(happened, sessionId, true),
                asSubscriber(event -> {
                    Happened happened = (Happened) event.value();
                    List<TurnId> turns = happened.turns();
                    listener.accept(new Numbered(event.seq(),
                            turns.isEmpty() ? null : turns.get(turns.size() - 1), happened.event()));
                }))::close;
    }

    /** The number of the newest event on the session's stream. */
    public long lastSeq(SessionId sessionId) {
        return streamChannel(rootOf(sessionId)).lastSeq();
    }

    /** The number of the oldest event the session's stream still keeps. */
    public long earliestBufferedSeq(SessionId sessionId) {
        return streamChannel(rootOf(sessionId)).earliestBufferedSeq();
    }

    /**
     * Follows one turn: the events of it and of the turns it starts, the
     * last of them its own end. A listener that comes late is told what the
     * channel still keeps of the turn; one that comes after the turn has
     * ended is told so all the same.
     *
     * @param turn how the turn stands when asked — the listener asks once it has caught up
     */
    Subscription follow(TurnId turnId, SessionId sessionId, Supplier<Optional<Turn>> turn,
                        SessionEventListener listener) {
        Channel<Carried> channel = streamChannel(rootOf(sessionId));
        AtomicBoolean over = new AtomicBoolean();
        Object self = new Object();
        Subscription following = channel.subscribe(0,
                carried -> switch (carried) {
                    case Happened happened -> happened.turns().contains(turnId);
                    case CaughtUp caughtUp -> caughtUp.listener() == self;
                },
                asSubscriber(event -> {
                    switch (event.value()) {
                        case Happened happened -> {
                            SessionEvent said = happened.event();
                            boolean itsEnd = turnId.equals(said.turnId())
                                    && (said.is(StreamEvent.DONE) || said.is(StreamEvent.ERROR));
                            if (itsEnd ? over.compareAndSet(false, true) : !over.get()) {
                                listener.onEvent(said);
                            }
                        }
                        // The end of a turn that ended long ago is no longer on the channel.
                        case CaughtUp caughtUp -> turn.get().filter(now -> now.status().isFinal()).ifPresent(now -> {
                            if (over.compareAndSet(false, true)) {
                                listener.onEvent(endOf(now));
                            }
                        });
                    }
                }))::close;
        channel.publish(new CaughtUp(self));
        return following;
    }

    /** The end of a turn as its event, made from the turn as it stands. */
    private static SessionEvent endOf(Turn turn) {
        if (turn.outcome() == null) {
            return SessionEvent.of(StreamEvent.ERROR, turn.sessionId(), turn.id(), turn.parent(),
                    new StreamEvent.Error(turn.status(), turn.error() != null ? turn.error()
                            : "The turn " + (turn.status() == TurnStatus.CANCELLED ? "was cancelled" : "failed")));
        }
        return SessionEvent.of(StreamEvent.DONE, turn.sessionId(), turn.id(), turn.parent(), new StreamEvent.Done(
                turn.status(), turn.outcome().text() == null ? "" : turn.outcome().text(),
                turn.outcome().incompleteReason() == null ? null : turn.outcome().incompleteReason().name()));
    }

    @Override
    public void publish(SessionEvent event) {
        // Whoever follows the turn it names hears it too.
        sessions.find(event.sessionId()).ifPresent(session -> publish(session,
                event.turnId() == null ? List.of() : List.of(event.turnId()), event));
    }

    /**
     * @param turns the turn the event belongs to and the tasks that one runs
     *              under, nearest first; empty for an event of no turn
     */
    void publish(Session session, List<TurnId> turns, SessionEvent event) {
        contributed.get().forEach(listener -> tell(() -> listener.onEvent(event)));
        Happened happened = new Happened(scope.namespace().value(), session, ancestorsOf(session),
                List.copyOf(turns), event);
        streamChannel(rootOf(session)).publish(happened);
    }

    private static boolean isUnder(Happened happened, SessionId sessionId, boolean childSessions) {
        return happened.session().id().equals(sessionId)
                || (childSessions && happened.sessionsAbove().contains(sessionId));
    }

    /** Runs what a listener is told as whoever subscribed, on the listener's own thread. */
    private <E> Consumer<Channel.Event<E>> asSubscriber(Consumer<Channel.Event<E>> told) {
        Scope subscriber = scope.get();
        return event -> scope.runIn(subscriber, () -> tell(() -> told.accept(event)));
    }

    private static void tell(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException e) {
            log.warn("A session event listener failed: {}", e.toString());
        }
    }

    /** Keyed by the namespace: the same session id elsewhere is another session. */
    private Channel<Carried> streamChannel(SessionId root) {
        return channels.channel("stream/" + scope.namespace().value() + "/" + root.value());
    }

    /** The session at the top of the tree this one is in. */
    private SessionId rootOf(Session session) {
        List<SessionId> above = ancestorsOf(session);
        return above.isEmpty() ? session.id() : above.get(above.size() - 1);
    }

    /** As {@link #rootOf(Session)}; the session itself, for one nobody knows. */
    private SessionId rootOf(SessionId sessionId) {
        return sessions.find(sessionId).map(this::rootOf).orElse(sessionId);
    }

    private List<SessionId> ancestorsOf(Session session) {
        return ancestors.computeIfAbsent(scope.namespace().value() + "/" + session.id().value(), id -> {
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
