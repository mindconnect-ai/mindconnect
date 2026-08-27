package ai.mindconnect.mcp.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Per-session cache of persistent {@link McpConnection}s, keyed by
 * {@code (sessionId, providerKey)}. Multiple tool calls against the same
 * MCP server within one agent session share a single underlying process —
 * which removes the ~1-3s container spawn cost on the hot path.
 *
 * <p>What this is and isn't:
 * <ul>
 *   <li><b>Per-session</b>, not global — two sessions hitting the same server
 *       still get two containers. Cross-session pooling is a v1+ concern
 *       (see {@code mc-mcp-gateway} in the concept).</li>
 *   <li><b>Idle-evicted</b> on a fixed schedule. There is no LRU cap because
 *       v0 deploys are single-user / few-sessions; if that changes we add
 *       size-based eviction here.</li>
 *   <li>The registry is the <em>owner</em> of every cached connection. Callers
 *       must <b>not</b> call {@link McpConnection#close()} on a returned
 *       handle — use {@link #closeSession} or {@link #shutdown}.</li>
 * </ul>
 */
public final class McpSessionRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpSessionRegistry.class);

    /** Default idle window before a cached connection is evicted. */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(30);
    /** Default cadence at which the idle-sweep runs. */
    public static final Duration DEFAULT_SWEEP_INTERVAL = Duration.ofMinutes(5);

    private record Key(UUID sessionId, String providerKey) {}

    private static final class Entry {
        final McpConnection connection;
        volatile Instant lastUsedAt;
        Entry(McpConnection connection) {
            this.connection = connection;
            this.lastUsedAt = Instant.now();
        }
    }

    private final McpProxy proxy;
    private final Duration idleTimeout;
    private final Map<Key, Entry> cache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;

    public McpSessionRegistry(McpProxy proxy) {
        this(proxy, DEFAULT_IDLE_TIMEOUT, DEFAULT_SWEEP_INTERVAL);
    }

    public McpSessionRegistry(McpProxy proxy, Duration idleTimeout, Duration sweepInterval) {
        this.proxy = proxy;
        this.idleTimeout = idleTimeout;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-session-idle-sweeper");
            t.setDaemon(true);
            return t;
        });
        long ms = sweepInterval.toMillis();
        sweeper.scheduleWithFixedDelay(this::evictIdle, ms, ms, TimeUnit.MILLISECONDS);
    }

    /**
     * Return a healthy {@link McpConnection} for {@code (sessionId, providerKey)},
     * spawning one via {@code proxy.connect(spawn)} if necessary. The spawn
     * descriptor is only consulted on miss (or when the cached connection is
     * stale) — callers can pass it eagerly without paying the build cost on
     * the hot path.
     *
     * <p>Thread-safe but not lock-free: connections are created under a
     * per-key lock to avoid double-spawn on first hit.
     */
    public McpConnection getOrOpen(UUID sessionId, String providerKey, McpStdioSpawn spawn) {
        Key key = new Key(sessionId, providerKey);
        Entry existing = cache.get(key);
        if (existing != null && existing.connection.isHealthy()) {
            existing.lastUsedAt = Instant.now();
            return existing.connection;
        }
        return openLocked(key, spawn);
    }

    private synchronized McpConnection openLocked(Key key, McpStdioSpawn spawn) {
        // Double-check inside the lock — another thread may have raced us.
        Entry existing = cache.get(key);
        if (existing != null && existing.connection.isHealthy()) {
            existing.lastUsedAt = Instant.now();
            return existing.connection;
        }
        if (existing != null) {
            // unhealthy → drop before replacing
            closeQuietly(existing.connection);
            cache.remove(key);
        }
        McpConnection fresh = proxy.connect(spawn);
        cache.put(key, new Entry(fresh));
        log.debug("McpSessionRegistry: opened connection for session={} provider={}",
                key.sessionId(), key.providerKey());
        return fresh;
    }

    /** Close + evict every cached connection for the given session. */
    public void closeSession(UUID sessionId) {
        Iterator<Map.Entry<Key, Entry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Entry> e = it.next();
            if (e.getKey().sessionId().equals(sessionId)) {
                closeQuietly(e.getValue().connection);
                it.remove();
            }
        }
    }

    /** Evict idle-too-long entries. Called by the sweeper thread. */
    void evictIdle() {
        Instant cutoff = Instant.now().minus(idleTimeout);
        Iterator<Map.Entry<Key, Entry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Entry> e = it.next();
            Entry entry = e.getValue();
            if (entry.lastUsedAt.isBefore(cutoff) || !entry.connection.isHealthy()) {
                closeQuietly(entry.connection);
                it.remove();
                log.debug("McpSessionRegistry: evicted idle session={} provider={}",
                        e.getKey().sessionId(), e.getKey().providerKey());
            }
        }
    }

    int size() { return cache.size(); }

    @Override
    public void close() { shutdown(); }

    /** Shut down the sweeper and close all cached connections. */
    public void shutdown() {
        sweeper.shutdownNow();
        for (Entry e : cache.values()) closeQuietly(e.connection);
        cache.clear();
    }

    private static void closeQuietly(McpConnection c) {
        try { c.close(); } catch (RuntimeException ignored) { /* logged inside */ }
    }
}
