package ai.mindconnect.channel;

import java.util.List;
import java.util.function.Consumer;

/**
 * The durable "main channel": every publish is appended to the
 * {@link ChannelStore} FIRST (which assigns the domain seq) and then fanned
 * out live under the same seq. {@code subscribe} carries the concept-12
 * reconnect bridge built in: replay from the STORE (not the live buffer —
 * so eviction and buffer size never cost correctness), then attach live,
 * gap-free and duplicate-free because both happen under the publish lock.
 *
 * <p>Pair it with a plain ephemeral {@link Channel} for high-volume deltas
 * (tokens) that must not be persisted — main channel durable, sub-channel
 * ephemeral.
 */
public final class PersistentChannel<E> {

    private static final int REPLAY_BATCH = 1000;

    private final String id;
    private final ChannelStore<E> store;
    private final Channel<E> live;

    PersistentChannel(String id, ChannelStore<E> store, Channel<E> live) {
        this.id = id;
        this.store = store;
        this.live = live;
    }

    public String id() {
        return id;
    }

    /** Durable append + live fan-out, one seq space. */
    public long publish(E value) {
        synchronized (this) {
            long seq = store.append(id, value);
            live.publishAt(seq, value);
            return seq;
        }
    }

    /**
     * Replays everything after {@code afterSeq} from the store, then
     * continues live. The replay is delivered on the caller's thread before
     * this method returns; live events follow on the subscription's drain
     * thread.
     */
    public Subscription subscribe(long afterSeq, Consumer<Channel.Event<E>> consumer) {
        // The bulk of the replay runs OUTSIDE the monitor: a big backlog with
        // a slow consumer (an SSE socket, say) must not stall every publisher
        // for its whole duration. Only the final catch-up — closing whatever
        // gap opened while we replayed — plus the live attach happen under
        // the lock, so the gap-free/duplicate-free handover still holds.
        long cursor = replayFrom(afterSeq, consumer);
        synchronized (this) {
            cursor = replayFrom(cursor, consumer);
            return live.subscribe(cursor, consumer);
        }
    }

    private long replayFrom(long afterSeq, Consumer<Channel.Event<E>> consumer) {
        long cursor = afterSeq;
        while (true) {
            List<Channel.Event<E>> batch = store.readAfter(id, cursor, REPLAY_BATCH);
            if (batch.isEmpty()) break;
            for (Channel.Event<E> event : batch) {
                consumer.accept(event);
                cursor = event.seq();
            }
            if (batch.size() < REPLAY_BATCH) break;
        }
        return cursor;
    }

    public long lastSeq() {
        return store.lastSeq(id);
    }
}
