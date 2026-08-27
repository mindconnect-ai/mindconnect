package ai.mindconnect.channel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * One id-keyed live stream: publish events, fan them out to 0..n subscribers,
 * replay the recent past from a ring buffer. Deliberately EPHEMERAL — the
 * channel is delivery, never storage (concept 12): durability lives in domain
 * records, and a channel can always be rebuilt from them.
 *
 * <p>Two guarantees, both from concept 2:
 * <ul>
 *   <li><b>The publisher never blocks.</b> Each subscriber has its own
 *       bounded queue drained by a virtual thread; a slow or broken consumer
 *       loses OLD events (drop-oldest), never slows the producer.</li>
 *   <li><b>Per-channel total order.</b> Every event gets a strictly
 *       increasing {@code seq}; {@code subscribe(afterSeq)} replays the
 *       buffered tail and continues live without gaps or duplicates.</li>
 * </ul>
 */
public final class Channel<E> {

    private static final Logger log = LoggerFactory.getLogger(Channel.class);

    /** An event with its position in this channel's total order. */
    public record Event<E>(long seq, E value) { }

    private final int bufferCapacity;
    private final int subscriberCapacity;
    private final Deque<Event<E>> buffer = new ArrayDeque<>();
    private final List<Slot<E>> slots = new CopyOnWriteArrayList<>();
    private volatile boolean closed;
    private long seq = 0;
    private volatile long lastActivityMs = System.currentTimeMillis();

    Channel(int bufferCapacity, int subscriberCapacity) {
        this.bufferCapacity = bufferCapacity;
        this.subscriberCapacity = subscriberCapacity;
    }

    /** Assigns the next seq, buffers, fans out. Never blocks, never throws. */
    public long publish(E value) {
        synchronized (this) {
            return publishAt(seq + 1, value);
        }
    }

    /**
     * Publishes under an EXTERNALLY assigned seq (must be greater than the
     * last one) — used by {@code PersistentChannel}, where the store assigns
     * the durable domain seq and the live channel mirrors it, so subscribers
     * see one seq space across replay and live.
     */
    public long publishAt(long externalSeq, E value) {
        lastActivityMs = System.currentTimeMillis();
        synchronized (this) {
            if (externalSeq <= seq) {
                throw new IllegalArgumentException("seq must increase: " + externalSeq + " <= " + seq);
            }
            seq = externalSeq;
            Event<E> event = new Event<>(externalSeq, value);
            buffer.addLast(event);
            if (buffer.size() > bufferCapacity) buffer.removeFirst();
            for (Slot<E> slot : slots) slot.offer(event);
            return externalSeq;
        }
    }

    /**
     * Replays buffered events with {@code seq > afterSeq}, then continues
     * live. Use {@code afterSeq = 0} for "everything the buffer still has"
     * and {@link #lastSeq()} for "live only". Closing the subscription stops
     * delivery and frees the drain thread.
     */
    public Subscription subscribe(long afterSeq, Consumer<Event<E>> consumer) {
        return subscribe(afterSeq, value -> true, consumer);
    }

    /**
     * Subscribe with a read-side SELECTION filter (e.g. "no token deltas").
     * Filters select, they never mutate — mutating events in flight would
     * fork the truth against the store replay (same seq, two contents).
     * Policy that changes events belongs at the producer or the transport
     * edge, deliberately not here.
     */
    public Subscription subscribe(long afterSeq, Predicate<E> filter, Consumer<Event<E>> consumer) {
        lastActivityMs = System.currentTimeMillis();
        Slot<E> slot = new Slot<>(subscriberCapacity, filter, consumer);
        synchronized (this) {
            // A get-then-subscribe can race the idle eviction: without this
            // check the subscriber attaches to an orphan no publisher will
            // ever reach again and goes silently deaf. Failing loudly lets
            // the caller fetch the channel from the registry again.
            if (closed) {
                throw new IllegalStateException(
                        "channel was evicted — fetch it from the registry again");
            }
            List<Event<E>> replay = new ArrayList<>();
            for (Event<E> event : buffer) {
                if (event.seq() > afterSeq) replay.add(event);
            }
            replay.forEach(slot::offer);
            slots.add(slot);
        }
        slot.start();
        return () -> {
            // Detach FIRST (no further events reach the slot), then let the
            // drain thread finish what is already queued: a subscriber that
            // closes right after the final event still receives it. The hard
            // cut-off stays with closeAllSubscriptions/eviction.
            slots.remove(slot);
            slot.finish();
        };
    }

    public synchronized long lastSeq() {
        return seq;
    }

    public synchronized long earliestBufferedSeq() {
        return buffer.isEmpty() ? seq : buffer.peekFirst().seq();
    }

    public int subscriberCount() {
        return slots.size();
    }

    /** Last publish or subscribe, epoch millis — the idle-eviction signal. */
    public long lastActivityMs() {
        return lastActivityMs;
    }

    void closeAllSubscriptions() {
        synchronized (this) {
            closed = true;
        }
        for (Slot<E> slot : slots) slot.stop();
        slots.clear();
    }

    /** One subscriber: bounded queue + virtual drain thread; overflow drops oldest. */
    private static final class Slot<E> {
        private final BlockingQueue<Event<E>> queue;
        private final Predicate<E> filter;
        private final Consumer<Event<E>> consumer;
        private volatile boolean open = true;
        private volatile boolean finishing;
        private Thread drain;

        Slot(int capacity, Predicate<E> filter, Consumer<Event<E>> consumer) {
            this.queue = new ArrayBlockingQueue<>(capacity);
            this.filter = filter;
            this.consumer = consumer;
        }

        void offer(Event<E> event) {
            try {
                if (!filter.test(event.value())) return;   // selection on the read side
            } catch (RuntimeException e) {
                log.warn("Subscription filter threw, skipping event: {}", e.toString());
                return;                           // broken filter = skip, never break the stream
            }
            while (!queue.offer(event)) {
                queue.poll();                     // drop-oldest — the producer never waits
            }
        }

        void start() {
            drain = Thread.ofVirtual().name("channel-subscriber").start(() -> {
                while (open && !finishing) {
                    try {
                        deliver(queue.take());
                    } catch (InterruptedException e) {
                        break;                    // stop() or finish() interrupts
                    }
                }
                if (!open) return;                // hard stop: discard the rest
                // graceful close: hand over what was already queued, then end
                Event<E> event;
                while ((event = queue.poll()) != null) {
                    deliver(event);
                }
            });
        }

        private void deliver(Event<E> event) {
            try {
                consumer.accept(event);
            } catch (RuntimeException e) {
                // a broken observer must never break the stream — but it is not silent
                log.warn("Channel subscriber threw: {}", e.toString());
            }
        }

        /** Hard: discard whatever is still queued (eviction, channel close). */
        void stop() {
            open = false;
            if (drain != null) drain.interrupt();
        }

        /** Graceful: no new events (caller detached the slot), queued ones still go out. */
        void finish() {
            finishing = true;
            if (drain != null) drain.interrupt();
        }
    }
}
