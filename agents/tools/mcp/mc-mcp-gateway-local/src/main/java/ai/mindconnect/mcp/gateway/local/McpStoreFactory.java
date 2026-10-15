package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.agent.ScopeSupplier;

import java.nio.file.Path;

/**
 * Where the gateway keeps its registrations and its discovery cache, in the
 * namespace the scope names per call. Files by default; the Postgres starter
 * contributes the tables under {@code mindconnect.persistence=postgres}.
 */
public interface McpStoreFactory {

    /** The registrations of the namespace {@code scope} names per call. */
    McpServerRepository serverRepository(ScopeSupplier scope);

    /** The discovery cache of the namespace {@code scope} names per call. */
    McpDiscoveryStore discoveryStore(ScopeSupplier scope);

    /**
     * Both as files under {@code <storageRoot>/<namespace>/system} —
     * {@code mcp-servers} and {@code mcp-schema-cache}.
     */
    static McpStoreFactory files(Path storageRoot) {
        return new McpStoreFactory() {
            @Override
            public McpServerRepository serverRepository(ScopeSupplier scope) {
                return new FileMcpServerRepository(storageRoot, scope);
            }

            @Override
            public McpDiscoveryStore discoveryStore(ScopeSupplier scope) {
                return new FileMcpDiscoveryStore(storageRoot, scope);
            }
        };
    }
}
