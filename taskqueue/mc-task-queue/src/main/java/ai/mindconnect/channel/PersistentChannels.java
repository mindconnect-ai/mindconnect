package ai.mindconnect.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for durable main channels, sharing one {@link ChannelStore}. The
 * live side is lazily materialized and evictable as always — after a crash
 * or eviction, {@link #channel(String)} rebuilds a channel whose subscribers
 * replay seamlessly from the store (the seq space is the store's).
 */
public final class PersistentChannels<E> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PersistentChannels.class);

    private final ChannelStore<E> store;
    private final ChannelRegistry liveRegistry;
    private final Map<String, PersistentChannel<E>> channels = new ConcurrentHashMap<>();
    private volatile boolean open = true;
    private volatile Thread sweeper;

    public PersistentChannels(ChannelStore<E> store, ChannelRegistry liveRegistry) {
        this.store = store;
        this.liveRegistry = liveRegistry;
    }

    public PersistentChannel<E> channel(String id) {
        return channels.computeIfAbsent(id,
                key -> new PersistentChannel<>(key, store, liveRegistry.channel(key)));
    }

    /**
     * Bounds every channel this registry manages, by age and/or by count: a
     * background sweeper drops events older than {@code maxAge}
     * ({@link ChannelStore#purgeOlderThan}) and keeps at most the newest
     * {@code keepLastEvents} ({@link ChannelStore#purgeBefore}) every
     * {@code interval}. Age is the safety net — old events leave even when
     * traffic stops; the count caps a burst. Null/zero disables either
     * limit; both disabled means no sweeper. Off by default — durable events
     * are kept until the operator decides otherwise, and this setting is
     * that decision. Idempotent across nodes: several sweepers on one
     * shared store just agree.
     */
    public PersistentChannels<E> withRetention(Duration maxAge, long keepLastEvents, Duration interval) {
        boolean byAge = maxAge != null && !maxAge.isZero();
        boolean byCount = keepLastEvents > 0;
        if ((!byAge && !byCount) || sweeper != null) {
            return this;
        }
        sweeper = Thread.ofVirtual().name("channel-retention").start(() -> {
            while (open) {
                try {
                    Thread.sleep(interval);
                    for (String id : channels.keySet()) {
                        int purged = 0;
                        if (byAge) {
                            purged += store.purgeOlderThan(id, java.time.Instant.now().minus(maxAge));
                        }
                        if (byCount) {
                            long last = store.lastSeq(id);
                            if (last > keepLastEvents) {
                                purged += store.purgeBefore(id, last - keepLastEvents + 1);
                            }
                        }
                        if (purged > 0) {
                            log.info("Retention: forgot {} event(s) on channel {}", purged, id);
                        }
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    log.warn("Channel retention sweep failed: {}", e.getMessage());
                }
            }
        });
        return this;
    }

    /** Drops the in-memory side; the store — the truth — is untouched. */
    public void remove(String id) {
        channels.remove(id);
        liveRegistry.remove(id);
    }

    @Override
    public void close() {
        open = false;
        Thread t = sweeper;
        if (t != null) {
            t.interrupt();
        }
    }
}
