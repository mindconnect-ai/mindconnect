package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.mcp.gateway.McpDiscovery;
import ai.mindconnect.mcp.gateway.McpServerId;

import java.util.Optional;

/**
 * Where the answers of {@code tools/list} are remembered, one per server —
 * the storage half of {@link McpDiscoveryCache}, which decides when to ask a
 * server and when to answer from here. Like {@link McpServerRepository} an
 * implementation detail of the local gateway, and bound to one namespace.
 *
 * <p>A cache, not a record: losing an entry costs a server start, nothing
 * more. So a store that cannot read or write an entry says so in the log and
 * carries on — {@link #find} answers empty, {@link #save} drops the answer.
 */
public interface McpDiscoveryStore {

    /** What was stored for {@code server}; empty when nothing was, or it cannot be read. */
    Optional<McpDiscovery> find(McpServerId server);

    /** Stores {@code discovery} for {@code server}, replacing what was there. */
    void save(McpServerId server, McpDiscovery discovery);

    /**
     * Forgets what was stored for {@code server}; a missing entry is not an error.
     *
     * @return whether there was an entry
     */
    boolean delete(McpServerId server);
}
