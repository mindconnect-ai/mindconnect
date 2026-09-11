package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.mcp.gateway.McpCaller;
import ai.mindconnect.mcp.gateway.McpDiscovery;
import ai.mindconnect.mcp.gateway.McpGateway;
import ai.mindconnect.mcp.gateway.McpGatewayException;
import ai.mindconnect.mcp.gateway.McpResult;
import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.mcp.gateway.McpServerInfo;
import ai.mindconnect.mcp.gateway.McpServerRegistration;
import ai.mindconnect.mcp.gateway.McpTool;
import ai.mindconnect.mcp.proxy.McpConnection;
import ai.mindconnect.mcp.proxy.McpEndpoint;
import ai.mindconnect.mcp.proxy.McpProxy;
import ai.mindconnect.mcp.proxy.McpSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The gateway running in the host's own JVM: registrations from a
 * repository, tools discovered from the servers and cached, calls handed to
 * the proxy over a connection pooled per session.
 *
 * <p>Discovery is cached twice on purpose — in memory for the catalog, which
 * is asked on every tool lookup, and on disk so a restart does not spawn
 * every registered server again. The memory half is keyed on the
 * repository's {@code version()}, so an edited registration takes effect
 * without a restart.
 *
 * <p>A server that cannot be reached costs its own tools and nothing else:
 * discovery failures are logged and remembered as "no tools" for this
 * repository version, so a broken registration neither blocks the catalog
 * nor retries on every keystroke.
 */
public final class LocalMcpGateway implements McpGateway, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LocalMcpGateway.class);

    private final McpServerRepository repository;
    private final McpProxy proxy;
    private final McpSessionRegistry sessions;
    private final McpDiscoveryCache discoveryCache;
    private final McpTargetEndpoints endpoints;

    /** Server → discovered tools, valid for {@link #cachedAtVersion}. */
    private final Map<McpServerId, List<McpTool>> toolsByServer = new ConcurrentHashMap<>();
    private volatile long cachedAtVersion = Long.MIN_VALUE;

    public LocalMcpGateway(McpServerRepository repository,
                           McpProxy proxy,
                           McpSessionRegistry sessions,
                           Path storageDir,
                           Namespace namespace,
                           String containerRuntime) {
        this.repository = repository;
        this.proxy = proxy;
        this.sessions = sessions;
        this.discoveryCache = new McpDiscoveryCache(storageDir, namespace);
        this.endpoints = new McpTargetEndpoints(ContainerBinary.detect(containerRuntime).orElse(null));
    }

    @Override
    public List<McpServerInfo> servers() {
        return enabledRegistrations().stream().map(McpServerRegistration::toInfo).toList();
    }

    @Override
    public List<McpTool> tools(McpServerId server) {
        Optional<McpServerRegistration> registration = enabledRegistrations().stream()
                .filter(r -> r.id().equals(server))
                .findFirst();
        if (registration.isEmpty()) {
            return List.of();
        }
        invalidateStaleCache();
        return toolsByServer.computeIfAbsent(server, key -> discover(registration.get()));
    }

    @Override
    public McpResult call(McpCaller caller, McpServerId server, String toolName, Map<String, Object> arguments) {
        McpServerRegistration registration = enabledRegistrations().stream()
                .filter(r -> r.id().equals(server))
                .findFirst()
                .orElseThrow(() -> new McpGatewayException("no enabled MCP server " + server));
        McpEndpoint endpoint = endpoints.toEndpoint(registration.target());
        try {
            McpConnection connection = sessions.getOrOpen(
                    caller.sessionId().value(), server.value(), endpoint);
            return connection.callTool(toolName, arguments);
        } catch (McpGatewayException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new McpGatewayException(
                    "MCP call '" + toolName + "' on server " + server + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void release(McpCaller caller) {
        sessions.closeSession(caller.sessionId().value());
    }

    /** Closes every pooled connection. The discovery cache on disk survives. */
    @Override
    public void close() {
        sessions.shutdown();
    }

    @Override
    public long catalogVersion() {
        return repository.version();
    }

    /**
     * Drops what was discovered for one server, in memory and on disk, and
     * closes its pooled connections — they still speak to the old image, URL
     * or token. Used by the admin side after a registration changed.
     */
    void forget(McpServerId server) {
        toolsByServer.remove(server);
        discoveryCache.invalidate(server);
        sessions.closeServer(server.value());
    }

    /** What was remembered for this server, without contacting it. */
    McpDiscovery remembered(McpServerId server) {
        return discoveryCache.remembered(server);
    }

    /**
     * Starts the described server once, lists its tools and shuts it down —
     * no cache, no pool, nothing kept. Used to try a registration out before
     * it is saved.
     */
    List<McpTool> probeTools(McpServerRegistration draft) {
        try (McpConnection connection = proxy.connect(endpoints.toEndpoint(draft.target()))) {
            return connection.listTools();
        }
    }

    private List<McpServerRegistration> enabledRegistrations() {
        return repository.findAll().stream().filter(McpServerRegistration::enabled).toList();
    }

    private List<McpTool> discover(McpServerRegistration registration) {
        try {
            return discoveryCache.loadOrFetch(registration.id(), proxy,
                    endpoints.toEndpoint(registration.target()));
        } catch (RuntimeException e) {
            log.warn("MCP server '{}' offers no tools: {}", registration.id(), e.toString());
            return List.of();
        }
    }

    private void invalidateStaleCache() {
        long current = repository.version();
        if (cachedAtVersion != current) {
            toolsByServer.clear();
            cachedAtVersion = current;
        }
    }
}
