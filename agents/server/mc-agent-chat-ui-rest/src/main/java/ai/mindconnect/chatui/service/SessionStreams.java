package ai.mindconnect.chatui.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One live {@link StreamBus} per chat session, outliving the turns that
 * publish into it. This is what lets a second client see tokens at all: a
 * bus that is created when a turn starts can only ever reach clients that
 * were already listening, and a client with nothing to listen to has no way
 * to find out that a turn began.
 *
 * <p>Clients therefore attach when they open the session — before anything
 * runs — and stay attached. A turn resolves the session's bus, publishes its
 * patches, and ends; the subscribers remain.
 *
 * <p><b>Only the current turn is replayed.</b> {@link #turnStarted} raises
 * the bus's replay floor, so a client joining a quiet session replays nothing
 * and simply reads the persisted history — which is the truth. Carrying a
 * finished turn's patches across the boundary would be actively wrong:
 * they are APPEND operations against a page that already contains what they
 * would append, so the messages would show up twice.
 *
 * <p>The heartbeat is also the liveness check. An idle SSE connection is cut
 * by proxies after 30-60s, so every {@link #HEARTBEAT} each bus sends an SSE
 * comment; a write that fails drops that subscriber. A bus with no
 * subscribers left and no turn running is discarded — the next attach
 * creates a fresh one.
 */
@Component
public class SessionStreams {

    private static final Logger log = LoggerFactory.getLogger(SessionStreams.class);

    /** Comfortably inside the 30-60s idle timeout of a typical proxy. */
    static final Duration HEARTBEAT = Duration.ofSeconds(25);

    private final Map<String, StreamBus> buses = new ConcurrentHashMap<>();

    /**
     * Channels with a turn currently publishing. A bus must not be evicted
     * while a turn holds a reference to it — the producer captured the
     * object, so dropping it from the map would send later joiners to a
     * different bus and they would see nothing.
     */
    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService pulse = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "chat-stream-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public SessionStreams() {
        pulse.scheduleWithFixedDelay(this::beat,
                HEARTBEAT.toSeconds(), HEARTBEAT.toSeconds(), TimeUnit.SECONDS);
    }

    /** The session's bus, created on first use. */
    public StreamBus bus(String channelId) {
        return buses.computeIfAbsent(channelId, key -> {
            log.debug("Opening session stream {}", key);
            return new StreamBus();
        });
    }

    /** The session's bus if one exists — no side effect. */
    public Optional<StreamBus> find(String channelId) {
        return Optional.ofNullable(buses.get(channelId));
    }

    /**
     * A turn begins publishing: pin the bus against eviction and move its
     * replay floor to here, so anyone joining from now on replays this turn
     * and nothing older.
     */
    public StreamBus turnStarted(String channelId) {
        busy.add(channelId);
        catchUps.remove(channelId);
        StreamBus bus = bus(channelId);
        bus.startTurn();
        return bus;
    }

    /** The turn is over. The bus stays; its subscribers keep listening. */
    public void turnEnded(String channelId) {
        busy.remove(channelId);
        catchUps.remove(channelId);
    }

    /**
     * The two patches that put a late joiner's DOM into the running turn's
     * current state: the one that CREATES the streaming reply bubble, and
     * the last one that filled it. Token patches carry the cumulative text
     * and REPLACE the bubble, so those two are the whole state — but the
     * replace lands nowhere if the joiner never saw the append that made
     * the bubble.
     *
     * <p>Rendered JSON rather than raw text on purpose: the producer has
     * the renderer, this layer must not grow one.
     */
    public record CatchUp(String bubblePatch, String textPatch) { }

    private final Map<String, CatchUp> catchUps = new ConcurrentHashMap<>();

    /** The first token's patch — the one that appends the reply bubble. */
    public void rememberBubble(String channelId, String patchJson) {
        catchUps.put(channelId, new CatchUp(patchJson, null));
    }

    /** The newest token patch; replaces the previous one. */
    public void rememberText(String channelId, String patchJson) {
        catchUps.computeIfPresent(channelId, (k, c) -> new CatchUp(c.bubblePatch(), patchJson));
    }

    /** What a client joining right now needs before the live feed. */
    public Optional<CatchUp> catchUp(String channelId) {
        return Optional.ofNullable(catchUps.get(channelId));
    }

    /**
     * Heartbeat every attached client, then discard whatever is left with no
     * listeners and no turn. Never lets one broken bus stop the sweep.
     */
    private void beat() {
        for (var entry : buses.entrySet()) {
            String channelId = entry.getKey();
            StreamBus bus = entry.getValue();
            try {
                bus.ping();
            } catch (RuntimeException e) {
                log.warn("Heartbeat failed for {}: {}", channelId, e.toString());
            }
            if (bus.subscriberCount() == 0 && !busy.contains(channelId)) {
                buses.remove(channelId, bus);
                log.debug("Closed idle session stream {}", channelId);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        pulse.shutdownNow();
        buses.values().forEach(StreamBus::closeAll);
        buses.clear();
    }
}
