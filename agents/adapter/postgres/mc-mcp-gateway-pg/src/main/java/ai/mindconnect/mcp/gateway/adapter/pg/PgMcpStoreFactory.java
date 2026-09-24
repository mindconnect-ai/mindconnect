package ai.mindconnect.mcp.gateway.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.mcp.gateway.local.FileMcpServerRepository;
import ai.mindconnect.mcp.gateway.local.McpDiscoveryStore;
import ai.mindconnect.mcp.gateway.local.McpServerRepository;
import ai.mindconnect.mcp.gateway.local.McpStoreFactory;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The MCP gateway's stores as Postgres tables, bound to one namespace; each is
 * created with its schema. A namespace's registrations are imported from its
 * files under {@code <dataDir>/<namespace>/system/mcp-servers} the first time
 * it is asked for — before anything, the bundled seed included, is written
 * there.
 */
public class PgMcpStoreFactory implements McpStoreFactory {

    private final Sql sql;
    private final Path dataDir;

    /**
     * @param dataDir where the file stores kept their data ({@code mindconnect.data.base-dir}) —
     *                read for the one-time import, never written
     */
    public PgMcpStoreFactory(Sql sql, Path dataDir) {
        this.sql = Objects.requireNonNull(sql, "sql");
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
    }

    @Override
    public McpServerRepository serverRepository(Namespace namespace) {
        PgMcpServerRepository repository = new PgMcpServerRepository(sql, namespace).initSchema();
        repository.importOnce(new FileMcpServerRepository(dataDir, namespace));
        return repository;
    }

    @Override
    public McpDiscoveryStore discoveryStore(Namespace namespace) {
        return new PgMcpDiscoveryStore(sql, namespace).initSchema();
    }
}
