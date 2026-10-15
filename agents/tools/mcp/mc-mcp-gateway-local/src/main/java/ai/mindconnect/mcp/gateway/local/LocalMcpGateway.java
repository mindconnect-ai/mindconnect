package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The gateway running in the host's own JVM: registrations from a
 * repository, tools discovered from the servers and cached, calls handed to
 * the proxy over a connection pooled per session.
 *
 * <p>Discovery is cached twice on purpose — in memory for the catalog, which
 * is asked on every tool lookup, and in a {@link McpDiscoveryStore} (files or
 * a table) so a restart does not spawn every registered server again. The
 * memory half is keyed on the repository's {@code version()}, so an edited
 * registration takes effect without a restart.
 *
 * <p>One gateway serves every namespace: registrations, discoveries and
 * pooled connections are those of the namespace the scope names per call.
 *
 * <p>A server that cannot be reached costs its own tools and nothing else:
 * discovery failures are logged and remembered as "no tools" until a
 * registration changes or somebody re-reads that server's tools, so a broken
 * registration neither blocks the catalog nor retries on every keystroke.
 */
public final class LocalMcpGateway implements McpGateway, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LocalMcpGateway.class);

    private final McpServerRepository repository;
    private final ScopeSupplier scope;
    private final McpProxy proxy;
    private final McpSessionRegistry sessions;
    private final McpDiscoveryCache discoveryCache;
    private final McpTargetEndpoints endpoints;

    /** Pool key ({@code <namespace>/<server>}) → discovered tools, valid while the namespace is at {@link #cachedAtVersion}. */
    private final Map<String, List<McpTool>> toolsByServer = new ConcurrentHashMap<>();
    /** Namespace → the repository version its entries in {@link #toolsByServer} were found at. */
    private final Map<String, Long> cachedAtVersion = new ConcurrentHashMap<>();
    /** The pool keys calls opened connections under — what {@link #close} and {@link #forget(Namespace)} close. */
    private final Set<String> pooled = ConcurrentHashMap.newKeySet();

    /**
     * Moves with every {@link #forget}. Dropping a discovery changes what
     * {@link #tools} answers without touching a registration, so the
     * repository's version alone does not show it — and the tool provider
     * notices a changed catalog by {@link #catalogVersion()} and nothing else.
     */
    private final AtomicLong discoveryGeneration = new AtomicLong();

    public LocalMcpGateway(McpServerRepository repository,
                           McpProxy proxy,
                           McpSessionRegistry sessions,
                           McpDiscoveryStore discoveries,
                           ScopeSupplier scope,
                           String containerRuntime,
                           McpStartPolicy startPolicy) {
        this.repository = repository;
        this.scope = scope;
        this.proxy = proxy;
        this.sessions = sessions;
        this.discoveryCache = new McpDiscoveryCache(discoveries);
        // Where containers may not start, no container runtime is looked for.
        String containerBinary = startPolicy.allowDocker()
                ? ContainerBinary.detect(containerRuntime).orElse(null)
                : null;
        this.endpoints = new McpTargetEndpoints(containerBinary, startPolicy);
        log.info("MCP servers started on this machine: process targets {}, docker targets {}",
                startPolicy.allowProcess() ? "allowed" : "switched off",
                startPolicy.allowDocker() ? "allowed" : "switched off");
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
        return toolsByServer.computeIfAbsent(providerKey(server), key -> discover(registration.get()));
    }

    @Override
    public McpResult call(McpCaller caller, McpServerId server, String toolName, Map<String, Object> arguments) {
        McpServerRegistration registration = enabledRegistrations().stream()
                .filter(r -> r.id().equals(server))
                .findFirst()
                .orElseThrow(() -> new McpGatewayException("no enabled MCP server " + server));
        McpEndpoint endpoint = endpoints.toEndpoint(registration.target());
        String key = providerKey(server);
        try {
            pooled.add(key);
            McpConnection connection = sessions.getOrOpen(caller.sessionId().value(), key, endpoint);
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

    /**
     * Closes the connections this gateway pooled. The registry shuts down with
     * the application, not here; the stored discovery cache survives.
     */
    @Override
    public void close() {
        pooled.forEach(sessions::closeServer);
        pooled.clear();
    }

    /**
     * Forgets a deleted namespace: what was discovered there, in memory, and its
     * pooled connections. Its registrations and stored discoveries went with its
     * directory or its rows.
     */
    public void forget(Namespace namespace) {
        String prefix = namespace.value() + "/";
        toolsByServer.keySet().removeIf(key -> key.startsWith(prefix));
        cachedAtVersion.remove(namespace.value());
        pooled.removeIf(key -> {
            if (!key.startsWith(prefix)) return false;
            sessions.closeServer(key);
            return true;
        });
        discoveryGeneration.incrementAndGet();
    }

    /** The pool's key for a server: qualified by the namespace of the call, since the pool serves every namespace. */
    private String providerKey(McpServerId server) {
        return scope.namespace().value() + "/" + server.value();
    }

    /**
     * The repository's version together with {@link #discoveryGeneration}: a
     * saved registration moves the first, "Re-read tools" only the second, and
     * either has to reach the tool names the provider keeps.
     */
    @Override
    public long catalogVersion() {
        return 31 * repository.version() + discoveryGeneration.get();
    }

    /**
     * Drops what was discovered for one server, in memory and in the store, and
     * closes its pooled connections — they still speak to the old image, URL
     * or token. Used by the admin side after a registration changed, and by
     * "Re-read tools".
     */
    void forget(McpServerId server) {
        String key = providerKey(server);
        toolsByServer.remove(key);
        discoveryCache.invalidate(server);
        sessions.closeServer(key);
        pooled.remove(key);
        // Last: a lookup that read the version before this line sees the new
        // one next time and rebuilds; one that reads it after this line finds
        // the discovery already gone. Moved first, a lookup in between would
        // file the old tools under the new version and keep them.
        discoveryGeneration.incrementAndGet();
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
        String namespace = scope.namespace().value();
        long current = repository.version();
        Long cached = cachedAtVersion.put(namespace, current);
        if (cached == null || cached != current) {
            toolsByServer.keySet().removeIf(key -> key.startsWith(namespace + "/"));
        }
    }
}
