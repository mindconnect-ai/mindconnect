package ai.mindconnect.agent.runtime.engine.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.port.in.SessionEventListener;
import ai.mindconnect.agent.runtime.api.port.in.SessionLifecycleListener;
import ai.mindconnect.agent.runtime.api.port.in.SessionStreamListener;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;
import ai.mindconnect.agent.runtime.api.port.in.TurnListener;
import ai.mindconnect.agent.runtime.api.port.out.SessionEventPublisher;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.scope.Scope;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.ChannelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands the events of sessions and turns to whoever listens, through
 * channels: one for the lifecycle of all sessions — few events, what a list
 * of sessions follows — and one for the stream of each tree of
 * sessions, a session with every session under it. The turn workers publish
 * here; the session service and the turn service subscribe here.
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
public final class SessionEventHub implements SessionEventPublisher, UserSessionFeed {

    private static final Logger log = LoggerFactory.getLogger(SessionEventHub.class);
    private static final Duration IDLE = Duration.ofMinutes(10);

    /** What travels on a stream channel. */
    private sealed interface Carried { }

    /**
     * An event with what a listener is chosen by: the session it happened
     * in, the sessions above that one, and the turn it belongs to with the
     * turns that one runs under.
     */
    private record Happened(String namespace, Session session, List<SessionId> sessionsAbove, List<TurnId> turns,
                            SessionEvent event) implements Carried { }

    /** A turn is over, as it ended: what whoever follows that turn is told last. */
    private record Ended(Turn turn) implements Carried { }

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

    /** Hears the lifecycle of the sessions of this namespace that {@code wants} picks, from now on. */
    Subscription lifecycle(Predicate<Session> wants, SessionLifecycleListener listener) {
        // Heard from the subscriber's namespace only: the same session id elsewhere is another session.
        String namespace = scope.namespace().value();
        Channel<Happened> channel = lifecycleChannel();
        return channel.subscribe(channel.lastSeq(),
                happened -> happened.namespace().equals(namespace) && wants.test(happened.session()),
                asSubscriber(event -> listener.onEvent((SessionEvent.Lifecycle) event.value().event())))::close;
    }

    /** Hears the lifecycle of one session, and with {@code childSessions} of every session under it. */
    Subscription lifecycle(SessionId sessionId, boolean childSessions, SessionLifecycleListener listener) {
        String namespace = scope.namespace().value();
        Channel<Happened> channel = lifecycleChannel();
        return channel.subscribe(channel.lastSeq(),
                happened -> happened.namespace().equals(namespace) && isUnder(happened, sessionId, childSessions),
                asSubscriber(event -> listener.onEvent((SessionEvent.Lifecycle) event.value().event())))::close;
    }

    @Override
    public Subscription follow(UserId user, long afterSeq, Consumer<Entry> listener) {
        return lifecycleChannel().subscribe(afterSeq,
                happened -> user.equals(happened.session().userId()),
                asSubscriber(event -> listener.accept(new Entry(event.seq(), event.value().namespace(),
                        event.value().session(), event.value().turns().size() > 1,
                        (SessionEvent.Lifecycle) event.value().event()))))::close;
    }

    @Override
    public long latestSeq() {
        return lifecycleChannel().lastSeq();
    }

    @Override
    public long earliestKeptSeq() {
        return lifecycleChannel().earliestBufferedSeq();
    }

    /** Hears one session as it happens, and with {@code childSessions} every session under it, from now on. */
    Subscription stream(SessionId sessionId, boolean childSessions, SessionStreamListener listener) {
        Channel<Carried> channel = streamChannel(rootOf(sessionId));
        return channel.subscribe(channel.lastSeq(),
                carried -> carried instanceof Happened happened && isUnder(happened, sessionId, childSessions),
                asSubscriber(event -> listener.onEvent(
                        (SessionEvent.Stream) ((Happened) event.value()).event())))::close;
    }

    /**
     * Follows one turn: what the loop reports of it and of the turns it runs
     * under it, then that it is over. A listener that comes late is told what
     * the channel still keeps of the turn; one that comes after the turn has
     * ended is told so all the same.
     *
     * @param turn how the turn stands when asked — the listener asks once it has caught up
     */
    Subscription follow(TurnId turnId, SessionId sessionId, Supplier<Optional<Turn>> turn, TurnListener listener) {
        Channel<Carried> channel = streamChannel(rootOf(sessionId));
        AtomicBoolean over = new AtomicBoolean();
        Object self = new Object();
        Subscription following = channel.subscribe(0,
                carried -> switch (carried) {
                    case Happened happened -> happened.turns().contains(turnId)
                            && happened.event() instanceof SessionEvent.TurnReported;
                    case Ended ended -> ended.turn().id().equals(turnId);
                    case CaughtUp caughtUp -> caughtUp.listener() == self;
                },
                asSubscriber(event -> {
                    switch (event.value()) {
                        case Happened happened -> {
                            if (!over.get()) {
                                listener.onEvent(((SessionEvent.TurnReported) happened.event()).event());
                            }
                        }
                        case Ended ended -> {
                            if (over.compareAndSet(false, true)) {
                                listener.onFinished(ended.turn());
                            }
                        }
                        // The end of a turn that ended long ago is no longer on the channel.
                        case CaughtUp caughtUp -> turn.get().filter(now -> now.status().isFinal()).ifPresent(now -> {
                            if (over.compareAndSet(false, true)) {
                                listener.onFinished(now);
                            }
                        });
                    }
                }))::close;
        channel.publish(new CaughtUp(self));
        return following;
    }

    @Override
    public void publish(SessionEvent event) {
        sessions.find(event.sessionId()).ifPresent(session -> publish(session, List.of(), event));
    }

    /**
     * @param turns the turn the event belongs to and the turns that one runs
     *              under, nearest first; empty for an event of no turn
     */
    void publish(Session session, List<TurnId> turns, SessionEvent event) {
        contributed.get().forEach(listener -> tell(() -> listener.onEvent(event)));
        Happened happened = new Happened(scope.namespace().value(), session, ancestorsOf(session),
                List.copyOf(turns), event);
        // Each family to its own channel: a fragment never reaches whoever follows a list of sessions.
        switch (event) {
            case SessionEvent.Lifecycle lifecycle -> lifecycleChannel().publish(happened);
            case SessionEvent.Stream stream -> streamChannel(rootOf(session)).publish(happened);
        }
    }

    /** The turn is over: whoever follows it is told, once and last. */
    void ended(Session session, Turn turn) {
        streamChannel(rootOf(session)).publish(new Ended(turn));
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

    /** One for all namespaces: a user's sessions are followed wherever they are, and the events are few. */
    private Channel<Happened> lifecycleChannel() {
        return channels.channel("lifecycle");
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
