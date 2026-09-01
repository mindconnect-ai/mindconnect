package ai.mindconnect.chatui.service;

import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.ChannelRegistry;
import ai.mindconnect.channel.Subscription;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-session multiplexing fan-out used by {@link ActiveStreams}. One instance
 * lives per chat session (see {@link SessionStreams}); producers call
 * {@link #publish} and every attached {@link SseEmitter} sees a copy.
 *
 * <p>The sequencing, the replay buffer and the fan-out belong to
 * {@link Channel} from the task queue — the same machinery the agent runtime's
 * own session channels run on. What is left here is the SSE edge: mapping
 * emitters to subscriptions, writing frames onto the wire, and keeping idle
 * connections alive.
 *
 * <p><b>Why not our own fan-out.</b> This class used to write to each
 * subscriber inline while holding the publish lock, so a subscriber whose
 * socket had stopped draining blocked the producer — and with it every other
 * subscriber. That was harmless while a stream served the one client that had
 * opened it. It stopped being harmless when several clients on one session
 * became the point. Channel gives each subscriber a bounded queue drained by
 * its own virtual thread, dropping the oldest events when one falls behind: a
 * slow reader loses history, never the turn, and never anybody else's.
 *
 * <p><b>The replay floor.</b> Patches are APPEND operations against a page
 * rendered from persisted history, so replaying a finished turn would add
 * every message a second time. A turn therefore raises the floor
 * ({@link #startTurn}), and no subscriber is replayed from before it. Clamping
 * rather than clearing: the events stay in the buffer for anything that
 * legitimately asks for them, and nothing has to reach into the channel.
 */
public final class StreamBus {

    /**
     * Replay depth, in events. A turn's token-by-token patches plus its task
     * cards stay well under this; the channel default is larger than the 200
     * this class used to keep, which only makes reconnects more forgiving.
     */
    public static final int BUFFER_SIZE = ChannelRegistry.DEFAULT_BUFFER;

    /**
     * One SSE frame. {@code seq} is the channel's position, filled in on the
     * way out. {@code name} is the SSE event name ({@code "patch"},
     * {@code "error"}, {@code "done"}); {@code data} is the payload.
     */
    public record Event(long seq, String name, String data) { }

    /** What travels on the channel: a frame that does not know its position. */
    record Frame(String name, String data) { }

    /**
     * Channels are normally handed out by a registry keyed by id. Here the bus
     * IS the identity — {@link SessionStreams} already keeps one per session —
     * so each bus owns a private registry with a single channel rather than
     * inventing a second id space.
     */
    private static final AtomicLong CHANNEL_IDS = new AtomicLong();

    private final ChannelRegistry registry = new ChannelRegistry();
    private final Channel<Frame> channel = registry.channel("chat-ui-" + CHANNEL_IDS.incrementAndGet());
    private final Map<SseEmitter, Subscription> subscriptions = new ConcurrentHashMap<>();

    /**
     * No subscriber is replayed from before this point. Raised at each turn
     * start, so a client attaching with an old cursor still sees only the turn
     * that is running.
     */
    private volatile long replayFloor;

    /** Records the event and broadcasts it. Never blocks on a slow reader. */
    public void publish(String name, String data) {
        channel.publish(new Frame(name, data));
    }

    /**
     * Everything published from here on belongs to a new turn; nothing older
     * may be replayed into a page that already renders it.
     */
    public void startTurn() {
        replayFloor = channel.lastSeq();
    }

    /**
     * Subscribes {@code emitter} to the live feed, replaying buffered events
     * after {@code lastSeq} first. Pass {@link #lastSeq()} to skip replay
     * entirely. The cursor is clamped to the current turn — see the class
     * comment.
     */
    public void attach(SseEmitter emitter, long lastSeq) {
        attach(emitter, lastSeq, List.of());
    }

    /**
     * Attach with a PRELUDE — the frames that bring a client joining mid-turn
     * up to the current state, sent ahead of the replay.
     *
     * <p>The cursor is read <em>before</em> the prelude goes out, so anything
     * published while it is being written is still delivered by the
     * subscription: no gap, and no duplicate either, since the subscription
     * starts from exactly that point. The old implementation needed a lock
     * held across both steps to get the same guarantee.
     */
    public void attach(SseEmitter emitter, long lastSeq, List<Event> prelude) {
        long cursor = Math.max(Math.max(lastSeq, replayFloor), 0);
        cursor = Math.min(cursor, channel.lastSeq());

        for (Event e : prelude) {
            if (!sendTo(emitter, e)) {
                return;                        // emitter already broken
            }
        }

        Subscription subscription = channel.subscribe(cursor,
                event -> sendTo(emitter, new Event(event.seq(),
                        event.value().name(), event.value().data())));
        Subscription previous = subscriptions.put(emitter, subscription);
        if (previous != null) {
            previous.close();                  // a re-attach of the same emitter
        }
    }

    /** Drops an emitter from the feed. No-op if it was not attached. */
    public void detach(SseEmitter emitter) {
        Subscription subscription = subscriptions.remove(emitter);
        if (subscription != null) {
            subscription.close();
        }
    }

    /** How many clients are currently attached. */
    public int subscriberCount() {
        return subscriptions.size();
    }

    /** The newest sequence published on this bus. */
    public long lastSeq() {
        return channel.lastSeq();
    }

    /**
     * Sends an SSE comment to every subscriber and drops those that fail. Two
     * jobs in one: it keeps alive a connection a proxy would otherwise cut as
     * idle, and a failed write is how a client that went away is noticed — the
     * channel deliberately does not drop a consumer that throws, so nothing
     * else would tell us.
     *
     * <p>Written straight to the emitters rather than published: a heartbeat
     * is not part of the stream's history and must not take a sequence number
     * or a buffer slot.
     */
    public void ping() {
        for (SseEmitter emitter : subscriptions.keySet()) {
            try {
                emitter.send(SseEmitter.event().comment("hb"));
            } catch (Exception e) {
                detach(emitter);
            }
        }
    }

    /**
     * Completes every attached subscriber and clears the list — the shutdown
     * path, so clients see a clean stream end rather than hanging on an open
     * SSE forever.
     */
    public void closeAll() {
        for (Map.Entry<SseEmitter, Subscription> entry : subscriptions.entrySet()) {
            entry.getValue().close();
            try {
                entry.getKey().complete();
            } catch (Exception ignore) {
                // already gone
            }
        }
        subscriptions.clear();
    }

    private static boolean sendTo(SseEmitter emitter, Event event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.seq()))
                    .name(event.name())
                    .data(event.data()));
            return true;
        } catch (Exception e) {
            // Emitter closed by the client, or a transport error. The
            // heartbeat reaps it; throwing here would only be logged by the
            // channel and must not take the drain thread down.
            return false;
        }
    }
}
