package ai.mindconnect.chatui.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-channel multiplexing fan-out used by {@link ActiveStreams}. One
 * instance is created per live SSE stream and lives on the channel's
 * {@link ActiveStreams.Handle}. Producers call {@link #publish} to push an
 * event; every currently-attached {@link SseEmitter} sees a copy.
 *
 * <p>Each event gets a monotonically-increasing sequence number, and the
 * last {@link #BUFFER_SIZE} events are kept in a ring buffer. A client
 * that reconnects with a {@code lastSeq} parameter via {@link #attach}
 * receives every event in the buffer with {@code seq > lastSeq} before
 * being subscribed to the live feed — closing the small race window
 * between a disconnect and a reconnect (typically a few seconds on F5).
 *
 * <p>Thread-safety:
 * <ul>
 *   <li>{@code publish} is synchronized so seq assignment and the
 *       buffer/subscriber writes are atomic.</li>
 *   <li>The subscriber list is a {@link CopyOnWriteArrayList} — fine for
 *       a low number of concurrent subscribers (1-3 in practice; one tab
 *       streaming, maybe one observer tab).</li>
 *   <li>{@link #attach} synchronizes against {@code publish} so a freshly
 *       attached subscriber can't miss an event that's in flight while
 *       the buffer-replay loop runs.</li>
 * </ul>
 */
public final class StreamBus {

    /**
     * Ring-buffer depth. Sized for a chat-turn's worth of token-by-token
     * patches plus task-card state changes — typical turn produces well
     * under 200 events. Bumping this is cheap (a few KB per stream).
     */
    public static final int BUFFER_SIZE = 200;

    /**
     * One past event held in the ring buffer. {@code seq} starts at 1 and
     * grows monotonically per stream. {@code name} is the SSE event name
     * (e.g. {@code "patch"}, {@code "error"}, {@code "done"}); {@code data}
     * is the JSON-serialised payload.
     */
    public record Event(long seq, String name, String data) { }

    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private final Deque<Event> buffer = new ArrayDeque<>(BUFFER_SIZE);
    private final AtomicLong seqGen = new AtomicLong(0);

    /**
     * Records the event in the ring buffer and broadcasts it to every
     * currently attached subscriber. Failures on a single subscriber drop
     * that subscriber from the list — its emitter is already broken at
     * that point, no benefit in retrying.
     */
    public synchronized void publish(String name, String data) {
        long seq = seqGen.incrementAndGet();
        Event event = new Event(seq, name, data);
        if (buffer.size() == BUFFER_SIZE) buffer.removeFirst();
        buffer.addLast(event);

        Iterator<SseEmitter> it = subscribers.iterator();
        while (it.hasNext()) {
            SseEmitter sub = it.next();
            if (!sendTo(sub, event)) subscribers.remove(sub);
        }
    }

    /**
     * Subscribes {@code emitter} to the live feed. If {@code lastSeq} is
     * non-negative, every buffered event with seq > lastSeq is replayed
     * synchronously before the emitter is added to the subscriber list —
     * the holding lock guarantees no live event interleaves.
     *
     * <p>Pass {@code -1} as {@code lastSeq} to replay everything in the
     * buffer (initial attach from a reconnecting client that has no seq
     * yet). Pass {@code Long.MAX_VALUE} to skip replay entirely (the
     * stream's own producer thread doesn't need replay).
     */
    public synchronized void attach(SseEmitter emitter, long lastSeq) {
        for (Event e : buffer) {
            if (e.seq() > lastSeq) {
                if (!sendTo(emitter, e)) return; // emitter already broken
            }
        }
        subscribers.add(emitter);
    }

    /** Drops an emitter from the subscriber list. No-op if not present. */
    public void detach(SseEmitter emitter) {
        subscribers.remove(emitter);
    }

    /** Last published seq, for clients that need to track "where am I". */
    public long lastSeq() {
        return seqGen.get();
    }

    /**
     * Completes every attached subscriber and clears the list. Called by the
     * channel's owner after the producer is fully done (after the {@code done}
     * event has been published) so reconnect-tabs see a clean stream end
     * rather than hanging on an open SSE forever.
     */
    public synchronized void closeAll() {
        for (SseEmitter sub : subscribers) {
            try { sub.complete(); } catch (Exception ignore) {}
        }
        subscribers.clear();
    }

    private static boolean sendTo(SseEmitter emitter, Event event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.seq()))
                    .name(event.name())
                    .data(event.data()));
            return true;
        } catch (Exception e) {
            // Emitter closed by client or transport error. Caller drops it.
            return false;
        }
    }
}
