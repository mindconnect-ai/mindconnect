package ai.mindconnect.agent.service.stream;

import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.ChannelRegistry;
import ai.mindconnect.channel.Subscription;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The turn's live stream, keyed by turn id — {@code Consumer<StreamEvent>}
 * stays the vocabulary, the transport becomes a channel (concept 16,
 * decision 3).
 *
 * <p>Why an id and not a passed-through consumer: a consumer hangs on the
 * object graph of ONE execution and cannot survive a suspension. A channel
 * id can — every execution of a turn task resolves its channel fresh
 * (concept 12), a resumed turn streams seamlessly on, and a reconnecting
 * client replays the ring-buffer tail via {@code subscribe(afterSeq)}.
 *
 * <p>The channel is delivery, never storage: the truth is the persisted
 * message list, so a missed event costs a rendering, not data. Channels with
 * no subscribers are evicted after idling {@link #IDLE_EVICTION}; a finished
 * turn's channel is dropped explicitly.
 */
public final class TurnChannels {

    private static final Duration IDLE_EVICTION = Duration.ofMinutes(10);

    private final ChannelRegistry registry;

    public TurnChannels() {
        this(new ChannelRegistry().withIdleEviction(IDLE_EVICTION));
    }

    public TurnChannels(ChannelRegistry registry) {
        this.registry = registry;
    }

    /** Where a turn publishes — resolved per call, so it survives suspensions. */
    public Consumer<StreamEvent> publisherFor(UUID turnId) {
        return event -> channel(turnId).publish(event);
    }

    /**
     * Attaches a live consumer from the beginning of the buffered tail.
     * The value reaching {@code consumer} is the plain {@link StreamEvent};
     * clients that want to cursor use {@link #subscribe(UUID, long, Consumer)}.
     */
    public Subscription subscribe(UUID turnId, Consumer<StreamEvent> consumer) {
        return channel(turnId).subscribe(0, event -> consumer.accept(event.value()));
    }

    /** Reconnect: replay everything after {@code afterSeq}, then continue live. */
    public Subscription subscribe(UUID turnId, long afterSeq,
                                  Consumer<Channel.Event<StreamEvent>> consumer) {
        return channel(turnId).subscribe(afterSeq, consumer);
    }

    /**
     * Drops a finished turn's channel. Late subscribers get a fresh, empty
     * channel — their replay is the conversation, not the buffer.
     */
    public void drop(UUID turnId) {
        registry.remove(id(turnId));
    }

    private Channel<StreamEvent> channel(UUID turnId) {
        return registry.channel(id(turnId));
    }

    private static String id(UUID turnId) {
        return "turn_" + turnId;
    }
}
