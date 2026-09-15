package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.initialdata.FileCopyInitialDataInstaller;
import ai.mindconnect.mcp.gateway.McpGateway;
import ai.mindconnect.mcp.gateway.McpRegistryAdmin;
import ai.mindconnect.mcp.proxy.McpProxy;
import ai.mindconnect.mcp.proxy.McpSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-process gateway, once per namespace. MCP registrations live in a
 * namespace like every other configuration — {@code <namespace>/system/mcp-servers}
 * — and a gateway caches what it discovered and holds the connections it
 * opened, so a namespace gets a gateway of its own, built on first use. The
 * default namespace is seeded with the bundled registrations at start-up;
 * a namespace somebody creates starts empty. The proxy and the session registry
 * are shared: a session belongs to one namespace, so its connections never
 * meet another's.
 *
 * <p>The beans the rest of the application sees — {@link McpServerRepository},
 * {@link McpGateway}, {@link McpRegistryAdmin} — are routing proxies over
 * this holder, choosing the namespace per call.
 */
public final class NamespacedMcpGateways implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NamespacedMcpGateways.class);

    /** One namespace's gateway and what it is built on. */
    public record Parts(FileMcpServerRepository repository, LocalMcpGateway gateway, LocalMcpRegistryAdmin admin) {}

    private final Path storageRoot;
    private final McpProxy proxy;
    private final McpSessionRegistry sessions;
    private final String containerRuntime;
    private final McpStartPolicy startPolicy;
    private final Map<Namespace, Parts> parts = new ConcurrentHashMap<>();

    public NamespacedMcpGateways(Path storageRoot, McpProxy proxy, McpSessionRegistry sessions,
                                 String containerRuntime, McpStartPolicy startPolicy) {
        this.storageRoot = storageRoot;
        this.proxy = proxy;
        this.sessions = sessions;
        this.containerRuntime = containerRuntime;
        this.startPolicy = startPolicy;
    }

    /**
     * Puts the bundled registrations ({@code initialData}, a classpath pattern) into
     * {@code namespace} — existing files are never overwritten. Only the default
     * namespace gets them, at start-up: a namespace somebody creates starts empty.
     */
    public void seed(Namespace namespace, String initialData) {
        new FileCopyInitialDataInstaller(forNamespace(namespace).repository().directory()).install(initialData);
    }

    /** The namespace's gateway, built and seeded on first use. */
    public Parts forNamespace(Namespace namespace) {
        return parts.computeIfAbsent(namespace, ns -> {
            FileMcpServerRepository repository = new FileMcpServerRepository(storageRoot, ns);
            LocalMcpGateway gateway = new LocalMcpGateway(repository, proxy, sessions, storageRoot, ns,
                    containerRuntime, startPolicy);
            log.info("MCP gateway opened for namespace '{}'", ns.value());
            return new Parts(repository, gateway, new LocalMcpRegistryAdmin(repository, gateway));
        });
    }

    @Override
    public void close() {
        parts.values().forEach(p -> p.gateway().close());
        parts.clear();
    }
}
