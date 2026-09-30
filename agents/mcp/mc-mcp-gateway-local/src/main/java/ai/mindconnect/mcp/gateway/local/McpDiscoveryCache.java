package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.mcp.gateway.McpDiscovery;
import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.mcp.gateway.McpTool;
import ai.mindconnect.mcp.proxy.McpConnection;
import ai.mindconnect.mcp.proxy.McpEndpoint;
import ai.mindconnect.mcp.proxy.McpProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A persistent cache of one MCP server's {@code tools/list} answer, one entry
 * per server, kept in a {@link McpDiscoveryStore} — files beside the
 * registrations ({@code <storage>/<namespace>/system/mcp-schema-cache/<id>.json})
 * or a table, as the installation's persistence is.
 *
 * <p>Discovery costs a container start, and the answer only changes when the
 * server's image does. Keeping it means a restart does not pay that price
 * again — the tool catalog is there before anything is called.
 *
 * <p>Invalidation is explicit: saving a registration or "Re-read tools" drops
 * the entry. No TTL, no image-tag check.
 *
 * <p>Grown out of {@code McpSchemaCache} in the gmail module, which said it
 * should be lifted "when a second MCP provider arrives" — this is that
 * moment.
 */
final class McpDiscoveryCache {

    private static final Logger log = LoggerFactory.getLogger(McpDiscoveryCache.class);

    private final McpDiscoveryStore store;

    McpDiscoveryCache(McpDiscoveryStore store) {
        this.store = store;
    }

    /** Cached tools of {@code serverId}, or the server's answer, then cached. */
    List<McpTool> loadOrFetch(McpServerId serverId, McpProxy proxy, McpEndpoint endpoint) {
        Optional<McpDiscovery> loaded = store.find(serverId);
        if (loaded.isPresent()) {
            if (!loaded.get().tools().isEmpty()) {
                log.debug("MCP discovery cache: {} tool(s) for '{}'", loaded.get().tools().size(), serverId);
                return loaded.get().tools();
            }
            log.warn("MCP discovery cache for '{}' was empty — rediscovering", serverId);
        }
        List<McpTool> fresh = fetch(serverId, proxy, endpoint);
        store.save(serverId, new McpDiscovery(Instant.now(), fresh));
        log.info("MCP discovery: cached {} tool(s) for '{}'", fresh.size(), serverId);
        return fresh;
    }

    /**
     * What was remembered for this server, without asking it. Empty when
     * nothing was ever cached or the entry is unreadable — a missing memory is
     * not an error, it just means the next lookup will do the work.
     */
    McpDiscovery remembered(McpServerId serverId) {
        return store.find(serverId).orElseGet(McpDiscovery::never);
    }

    /** Drops the cached answer so the next lookup asks the server again. */
    void invalidate(McpServerId serverId) {
        if (store.delete(serverId)) {
            log.info("MCP discovery cache for '{}' dropped", serverId);
        }
    }

    private List<McpTool> fetch(McpServerId serverId, McpProxy proxy, McpEndpoint endpoint) {
        log.info("MCP discovery: asking '{}' for its tools", serverId);
        try (McpConnection connection = proxy.connect(endpoint)) {
            return connection.listTools();
        }
    }
}
