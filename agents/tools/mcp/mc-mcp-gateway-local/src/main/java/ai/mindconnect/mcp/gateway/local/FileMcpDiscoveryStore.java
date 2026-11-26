package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.common.util.AtomicFiles;
import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.mcp.gateway.McpDiscovery;
import ai.mindconnect.mcp.gateway.McpServerId;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Discoveries as one JSON file per server, beside the registrations:
 * {@code <storage>/<namespace>/system/mcp-schema-cache/<id>.json}, the
 * namespace the scope names for the current call.
 */
public final class FileMcpDiscoveryStore implements McpDiscoveryStore {

    private static final Logger log = LoggerFactory.getLogger(FileMcpDiscoveryStore.class);

    private final Path storageDir;
    private final ScopeSupplier scope;
    private final ObjectMapper mapper;
    private final McpDiscoveryJson discoveries;

    public FileMcpDiscoveryStore(Path storageDir, ScopeSupplier scope, JsonMapperFactory json) {
        this.storageDir = storageDir.toAbsolutePath();
        this.scope = scope;
        this.mapper = json.create()
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.discoveries = new McpDiscoveryJson(json);
    }

    @Override
    public Optional<McpDiscovery> find(McpServerId server) {
        Path file = fileFor(server);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(discoveries.read(mapper.readTree(file.toFile())));
        } catch (IOException | RuntimeException e) {
            log.warn("MCP discovery cache {} unreadable: {}", file, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void save(McpServerId server, McpDiscovery discovery) {
        Path file = fileFor(server);
        try {
            AtomicFiles.write(file, out -> mapper.writeValue(out,
                    discoveries.write(discovery, JsonNodeFactory.instance)));
        } catch (IOException e) {
            // A cache that cannot be written is a slow start-up, not a failure.
            log.warn("cannot write MCP discovery cache {}: {}", file, e.toString());
        }
    }

    @Override
    public boolean delete(McpServerId server) {
        try {
            return Files.deleteIfExists(fileFor(server));
        } catch (IOException e) {
            log.warn("cannot drop MCP discovery cache for '{}': {}", server, e.toString());
            return false;
        }
    }

    /** The id is a safe file name — {@link McpServerId} refuses anything else. */
    private Path fileFor(McpServerId server) {
        return directory().resolve(server.value() + ".json");
    }

    /** The cache directory of the namespace the current call works in. */
    private Path directory() {
        return storageDir.resolve(scope.namespace().value()).resolve("system").resolve("mcp-schema-cache");
    }
}
