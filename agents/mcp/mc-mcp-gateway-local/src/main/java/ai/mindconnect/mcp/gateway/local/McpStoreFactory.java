package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.agent.Namespace;

import java.nio.file.Path;

/**
 * Where the gateway of one namespace keeps its registrations and its
 * discovery cache. Files by default; the Postgres starter contributes the
 * tables under {@code mindconnect.persistence=postgres}, and
 * {@link NamespacedMcpGateways} asks this once per namespace, on first use.
 */
public interface McpStoreFactory {

    /** The registrations of {@code namespace}. */
    McpServerRepository serverRepository(Namespace namespace);

    /** The discovery cache of {@code namespace}. */
    McpDiscoveryStore discoveryStore(Namespace namespace);

    /**
     * Both as files under {@code <storageRoot>/<namespace>/system} —
     * {@code mcp-servers} and {@code mcp-schema-cache}.
     */
    static McpStoreFactory files(Path storageRoot) {
        return new McpStoreFactory() {
            @Override
            public McpServerRepository serverRepository(Namespace namespace) {
                return new FileMcpServerRepository(storageRoot, namespace);
            }

            @Override
            public McpDiscoveryStore discoveryStore(Namespace namespace) {
                return new FileMcpDiscoveryStore(storageRoot, namespace);
            }
        };
    }
}
