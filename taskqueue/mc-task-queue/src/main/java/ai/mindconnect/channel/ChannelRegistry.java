package ai.mindconnect.channel;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Id-keyed channels, LAZILY materialized (concept 12): the first publish or
 * subscribe for an id creates the channel; after a crash or on another node
 * it simply materializes again and the subscriber replays the durable truth
 * from domain storage before going live.
 *
 * <p>Convention in the agent runtime: {@code channelId = responseId}.
 * Workers carry the id as DATA and look their emitter up per execution —
 * never holding channel objects across runs (concept 12's one rule).
 *
 * <p><b>No leaks, no persistence:</b> stale channels are EVICTED, never
 * saved — their content is derivable from domain storage, so "sleeping" a
 * channel is deleting it and "waking" it is the next {@link #channel} call.
 * Two eviction paths: the owner calls {@link #remove} once the underlying
 * work is terminal (plus a grace period), and {@link #evictIdle} sweeps
 * channels with no subscribers and no recent activity ({@link #withIdleEviction}
 * runs that sweep on a daemon thread). Note the seq consequence: a
 * re-materialized channel numbers from 1 again — clients cursor on the
 * DOMAIN seq (persisted items), the channel seq is transport-internal per
 * materialization.
 */
public final class ChannelRegistry {

    public static final int DEFAULT_BUFFER = 2048;
    public static final int DEFAULT_SUBSCRIBER_QUEUE = 4096;

    private final Map<String, Channel<?>> channels = new ConcurrentHashMap<>();
    private final List<ChannelLifecycleListener> listeners = new CopyOnWriteArrayList<>();
    private final int bufferCapacity;
    private final int subscriberCapacity;

    public ChannelRegistry() {
        this(DEFAULT_BUFFER, DEFAULT_SUBSCRIBER_QUEUE);
    }

    public ChannelRegistry(int bufferCapacity, int subscriberCapacity) {
        this.bufferCapacity = bufferCapacity;
        this.subscriberCapacity = subscriberCapacity;
    }

    public ChannelRegistry addListener(ChannelLifecycleListener listener) {
        listeners.add(listener);
        return this;
    }

    /** Gets or lazily creates the channel for {@code id}. */
    public <E> Channel<E> channel(String id) {
        return (Channel<E>) channels.computeIfAbsent(id, key -> {
            notifyListeners(l -> l.onMaterialized(key));
            return new Channel<>(bufferCapacity, subscriberCapacity);
        });
    }

    public <E> Optional<Channel<E>> find(String id) {
        return Optional.ofNullable((Channel<E>)channels.get(id));
    }

    /**
     * Drops the channel (e.g. after a grace period once its response is
     * terminal). Late subscribers get a fresh, empty channel and replay from
     * domain storage instead.
     */
    public void remove(String id) {
        Channel<?> channel = channels.remove(id);
        if (channel != null) {
            channel.closeAllSubscriptions();
            notifyListeners(l -> l.onEvicted(id));
        }
    }

    public int size() {
        return channels.size();
    }

    /**
     * Evicts every channel with zero subscribers and no activity for
     * {@code maxIdle}. Returns the number evicted. Safe to call anytime —
     * a false-positive eviction only costs the next caller a lazy
     * re-materialization plus replay from domain storage.
     */
    public int evictIdle(Duration maxIdle) {
        long cutoff = System.currentTimeMillis() - maxIdle.toMillis();
        int evicted = 0;
        for (Map.Entry<String, Channel<?>> entry : channels.entrySet()) {
            Channel<?> channel = entry.getValue();
            if (channel.subscriberCount() == 0 && channel.lastActivityMs() < cutoff) {
                if (channels.remove(entry.getKey(), channel)) {
                    channel.closeAllSubscriptions();
                    notifyListeners(l -> l.onEvicted(entry.getKey()));
                    evicted++;
                }
            }
        }
        return evicted;
    }

    private void notifyListeners(Consumer<ChannelLifecycleListener> callback) {
        for (ChannelLifecycleListener listener : listeners) {
            try {
                callback.accept(listener);
            } catch (RuntimeException ignored) {
                // observation must never break the registry
            }
        }
    }

    /** Starts a daemon sweeper calling {@link #evictIdle(Duration)} periodically. */
    public ChannelRegistry withIdleEviction(Duration maxIdle) {
        Thread.ofVirtual().name("channel-eviction").start(() -> {
            long sleepMs = Math.max(1000, maxIdle.toMillis() / 4);
            while (true) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    return;
                }
                evictIdle(maxIdle);
            }
        });
        return this;
    }
}
