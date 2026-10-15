package ai.mindconnect.mcp.gateway.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.mcp.gateway.local.FileMcpServerRepository;
import ai.mindconnect.mcp.gateway.local.McpDiscoveryStore;
import ai.mindconnect.mcp.gateway.local.McpServerRepository;
import ai.mindconnect.mcp.gateway.local.McpStoreFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The MCP gateway's stores as Postgres tables, in the namespace the scope
 * names per call; each is created with its schema. The registrations every
 * namespace kept in files under {@code <dataDir>/<namespace>/system/mcp-servers}
 * are imported when the store is built — before anything, the bundled seed
 * included, is written there.
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
    public McpServerRepository serverRepository(ScopeSupplier scope) {
        PgMcpServerRepository repository = new PgMcpServerRepository(sql, scope).initSchema();
        for (Namespace namespace : namespacesWithFiles()) {
            ScopeSupplier only = ScopeSupplier.fixed(namespace);
            new PgMcpServerRepository(sql, only).importOnce(new FileMcpServerRepository(dataDir, only));
        }
        return repository;
    }

    @Override
    public McpDiscoveryStore discoveryStore(ScopeSupplier scope) {
        return new PgMcpDiscoveryStore(sql, scope).initSchema();
    }

    /**
     * The namespaces the file stores kept registrations for: which one a call
     * works in is not known when the store is built, so each is imported up front.
     */
    private List<Namespace> namespacesWithFiles() {
        if (!Files.isDirectory(dataDir)) return List.of();
        List<Namespace> found = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(dataDir)) {
            for (Path dir : dirs.sorted().toList()) {
                if (!Files.isDirectory(dir.resolve("system").resolve("mcp-servers"))) continue;
                try {
                    found.add(new Namespace(dir.getFileName().toString()));
                } catch (IllegalArgumentException notANamespace) {
                    // a directory beside the namespaces, not one of them
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list the namespaces in " + dataDir, e);
        }
        return found;
    }
}
