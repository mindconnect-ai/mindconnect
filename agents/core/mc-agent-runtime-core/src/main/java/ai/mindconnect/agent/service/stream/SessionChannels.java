package ai.mindconnect.agent.service.stream;

import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.ChannelRegistry;
import ai.mindconnect.channel.Subscription;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The session's live stream — one ring-buffered channel per session, every
 * event stamped with its turn coordinates by the {@link SessionEvent}
 * envelope. Turns publish into it, clients attach to it; a turn-scoped view
 * is a filter on the same pipe, never a second channel.
 *
 * <p>Why the session and not the turn as the key: the channel's one
 * monotonic sequence is what makes reconnect trivial ({@code afterSeq} is a
 * plain cursor), and a cursor that survives turn boundaries must live above
 * them. A client that was away while one turn ended and the next began
 * resumes with the same number — with per-turn channels it would need to
 * discover ids and stitch streams.
 *
 * <p>Why an id and not a passed-through consumer: a consumer hangs on the
 * object graph of ONE execution and cannot survive a suspension. A channel
 * id can — every execution of a turn task resolves its channel fresh
 * (concept 12), a resumed turn streams seamlessly on, and a reconnecting
 * client replays the ring-buffer tail via {@code subscribe(afterSeq)}.
 *
 * <p>The channel is delivery, never storage: the truth is the persisted
 * message list, so a missed event costs a rendering, not data. Channels with
 * no subscribers are evicted after idling {@link #IDLE_EVICTION} — that is
 * the only cleanup; finished turns leave their tail in the buffer for late
 * reconnects until the session goes quiet.
 */
public final class SessionChannels {

    private static final Duration IDLE_EVICTION = Duration.ofMinutes(10);

    private final ChannelRegistry registry;

    public SessionChannels() {
        this(new ChannelRegistry().withIdleEviction(IDLE_EVICTION));
    }

    public SessionChannels(ChannelRegistry registry) {
        this.registry = registry;
    }

    /**
     * Where a turn execution publishes — resolved per call, so it survives
     * suspensions. The envelope stamps every event with its origin.
     */
    public Consumer<StreamEvent> publisherFor(UUID sessionId, UUID turnId, int run) {
        return event -> channel(sessionId).publish(new SessionEvent(turnId, run, event));
    }

    /**
     * Attach to the session's stream: replay everything after
     * {@code afterSeq} from the buffer, then continue live. The event's
     * {@code seq} is the cursor for the next reconnect.
     */
    public Subscription subscribe(UUID sessionId, long afterSeq,
                                  Consumer<Channel.Event<SessionEvent>> consumer) {
        return channel(sessionId).subscribe(afterSeq, consumer);
    }

    /**
     * The turn-scoped view: only {@code turnId}'s events, live from now on —
     * no replay. This is what a freshly submitted turn's caller wants, and
     * what the sub-agent mirror wants: the events of one execution, not the
     * session's history.
     */
    public Subscription subscribeTurn(UUID sessionId, UUID turnId,
                                      Consumer<StreamEvent> consumer) {
        Channel<SessionEvent> channel = channel(sessionId);
        return channel.subscribe(channel.lastSeq(),
                event -> turnId.equals(event.turnId()),
                event -> consumer.accept(event.value().event()));
    }

    /** The newest sequence the session has seen (0 when nothing happened). */
    public long lastSeq(UUID sessionId) {
        return channel(sessionId).lastSeq();
    }

    /** The oldest sequence still in the buffer — everything before it is gone. */
    public long earliestBufferedSeq(UUID sessionId) {
        return channel(sessionId).earliestBufferedSeq();
    }

    private Channel<SessionEvent> channel(UUID sessionId) {
        return registry.channel(id(sessionId));
    }

    private static String id(UUID sessionId) {
        return "session_" + sessionId;
    }
}
