package ai.mindconnect.chatui.service;

import ai.mindconnect.common.Cancellation;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side registry of currently-live SSE streams. Generic for any
 * stream that wants to expose itself to the admin-UI's "is something
 * still running?" query — chat turns, background research jobs, batch
 * imports, etc.
 *
 * <p>Keyed by {@code channelId} — the same identifier the client sees in
 * the {@code Sui-Stream-Channel} response header on the SSE response. The
 * client uses it to detect re-mount and replay buffered patches; the
 * server uses it to answer "is this stream still running?" and to cancel
 * on demand.
 *
 * <p>Each entry holds a {@link Handle} carrying lifecycle metadata plus a
 * caller-supplied {@code cancel} callback. The owning endpoint is
 * responsible for registering on stream-start and deregistering on
 * {@code onCompletion}/{@code onError}/{@code onTimeout}. Registry is
 * intentionally process-local (in-memory ConcurrentHashMap) — multi-node
 * deployments would need a Redis-backed implementation, but for the
 * current single-node admin-ui this keeps the moving parts minimal.
 */
@Component
public class ActiveStreams {

    /**
     * One live SSE stream. The {@link StreamBus} is the multiplex fan-out
     * the stream's producer publishes to; subscribers (the original POST
     * connection plus any GET-based reconnects) attach to it via
     * {@link StreamBus#attach}.
     *
     * @param channelId  client-facing stream id; same as the
     *                   {@code Sui-Stream-Channel} response header.
     * @param label      human-readable label (used by status toasts).
     * @param returnHref URL that re-renders the page that started the
     *                   stream — used by the floating status toast as
     *                   click target.
     * @param startedAt  registration time, for "running since" displays.
     * @param cancel     callback invoked by {@link #cancel(String)}; the
     *                   endpoint that registered the stream supplies this
     *                   when it wires up cancellation (e.g.
     *                   {@code chatService::cancelChat}).
     * @param bus        per-channel multiplex bus + ring buffer. Producers
     *                   publish via {@code bus.publish(name, data)};
     *                   reconnecting subscribers attach via
     *                   {@code bus.attach(emitter, lastSeq)}.
     */
    public record Handle(String channelId, String label, String returnHref,
                         Instant startedAt, Runnable cancel, StreamBus bus) { }

    private final Map<String, Handle> streams = new ConcurrentHashMap<>();

    /**
     * Registers (or replaces) the stream with id {@code channelId}.
     * Replace-semantics rather than reject-duplicate so a stale entry
     * from a crashed run doesn't permanently block a re-attempt — the
     * latest registration wins, which matches the "live reader keeps
     * reading" model.
     */
    public void register(Handle handle) {
        streams.put(handle.channelId(), handle);
    }

    /** Drops the stream from the registry. No-op if it wasn't there. */
    public void deregister(String channelId) {
        streams.remove(channelId);
    }

    /**
     * Invokes the cancel callback on the named stream, if present. Returns
     * {@code true} if a cancel was dispatched, {@code false} when the
     * stream wasn't registered (already finished, never started, …).
     *
     * <p>Cancellation is cooperative: the registered callback typically
     * flips a token the streaming worker polls at safe checkpoints; the
     * stream then ends and deregisters itself via its lifecycle hooks.
     */
    public boolean cancel(String channelId) {
        Handle h = streams.get(channelId);
        if (h == null) return false;
        try { h.cancel().run(); } catch (Exception ignore) {}
        return true;
    }

    public Optional<Handle> findHandle(String channelId) {
        return Optional.ofNullable(streams.get(channelId));
    }

    public Collection<Handle> snapshot() {
        return streams.values();
    }
}
