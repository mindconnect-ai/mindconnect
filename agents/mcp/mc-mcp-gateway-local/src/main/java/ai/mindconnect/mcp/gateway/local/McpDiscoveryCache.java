package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.mcp.gateway.McpDiscovery;
import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.mcp.gateway.McpTool;
import ai.mindconnect.mcp.proxy.McpConnection;
import ai.mindconnect.mcp.proxy.McpEndpoint;
import ai.mindconnect.mcp.proxy.McpProxy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Disk-backed cache of one MCP server's {@code tools/list} answer, one file
 * per server, beside the registrations:
 * {@code <storage>/<namespace>/system/mcp-schema-cache/<id>.json}.
 *
 * <p>Discovery costs a container start, and the answer only changes when the
 * server's image does. Caching it on disk means a restart does not pay that
 * price again — the tool catalog is there before anything is called.
 *
 * <p>Invalidation is explicit: saving a registration or "Re-read tools" drops
 * the file. No TTL, no image-tag check.
 *
 * <p>Grown out of {@code McpSchemaCache} in the gmail module, which said it
 * should be lifted "when a second MCP provider arrives" — this is that
 * moment.
 */
final class McpDiscoveryCache {

    private static final Logger log = LoggerFactory.getLogger(McpDiscoveryCache.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path directory;

    McpDiscoveryCache(Path storageDir, Namespace namespace) {
        this.directory = storageDir.resolve(namespace.value()).resolve("system")
                .resolve("mcp-schema-cache").toAbsolutePath();
    }

    /** Cached tools of {@code serverId}, or the server's answer, then cached. */
    List<McpTool> loadOrFetch(McpServerId serverId, McpProxy proxy, McpEndpoint endpoint) {
        Path file = fileFor(serverId);
        if (Files.isRegularFile(file)) {
            try {
                CacheFile loaded = MAPPER.readValue(file.toFile(), CacheFile.class);
                if (loaded.tools != null && !loaded.tools.isEmpty()) {
                    log.debug("MCP discovery cache: {} tool(s) for '{}' from {}",
                            loaded.tools.size(), serverId, file);
                    return toDomain(loaded.tools);
                }
                log.warn("MCP discovery cache {} was empty — rediscovering", file);
            } catch (IOException e) {
                log.warn("MCP discovery cache {} unreadable ({}) — rediscovering", file, e.toString());
            }
        }
        List<McpTool> fresh = fetch(serverId, proxy, endpoint);
        write(serverId, fresh);
        return fresh;
    }

    /**
     * What was remembered for this server, without asking it. Empty when
     * nothing was ever cached or the file is unreadable — a missing memory is
     * not an error, it just means the next lookup will do the work.
     */
    McpDiscovery remembered(McpServerId serverId) {
        Path file = fileFor(serverId);
        if (!Files.isRegularFile(file)) {
            return McpDiscovery.never();
        }
        try {
            CacheFile loaded = MAPPER.readValue(file.toFile(), CacheFile.class);
            Instant fetchedAt = loaded.fetchedAt == null ? null : Instant.parse(loaded.fetchedAt);
            return new McpDiscovery(fetchedAt,
                    loaded.tools == null ? List.of() : toDomain(loaded.tools));
        } catch (IOException | RuntimeException e) {
            log.debug("MCP discovery cache {} unreadable: {}", file, e.toString());
            return McpDiscovery.never();
        }
    }

    /** Drops the cached answer so the next lookup asks the server again. */
    void invalidate(McpServerId serverId) {
        try {
            if (Files.deleteIfExists(fileFor(serverId))) {
                log.info("MCP discovery cache for '{}' dropped", serverId);
            }
        } catch (IOException e) {
            log.warn("cannot drop MCP discovery cache for '{}': {}", serverId, e.toString());
        }
    }

    private List<McpTool> fetch(McpServerId serverId, McpProxy proxy, McpEndpoint endpoint) {
        log.info("MCP discovery: asking '{}' for its tools", serverId);
        try (McpConnection connection = proxy.connect(endpoint)) {
            return connection.listTools();
        }
    }

    private void write(McpServerId serverId, List<McpTool> tools) {
        Path file = fileFor(serverId);
        try {
            Files.createDirectories(file.getParent());
            CacheFile content = new CacheFile();
            content.fetchedAt = Instant.now().toString();
            content.tools = new ArrayList<>(tools.size());
            for (McpTool tool : tools) {
                CachedTool cached = new CachedTool();
                cached.name = tool.name();
                cached.description = tool.description();
                cached.inputSchema = tool.inputSchema();
                content.tools.add(cached);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            MAPPER.writeValue(tmp.toFile(), content);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("MCP discovery: cached {} tool(s) for '{}'", tools.size(), serverId);
        } catch (IOException e) {
            // A cache that cannot be written is a slow start-up, not a failure.
            log.warn("cannot write MCP discovery cache {}: {}", file, e.toString());
        }
    }

    /** The id is a safe file name — {@link McpServerId} refuses anything else. */
    private Path fileFor(McpServerId serverId) {
        return directory.resolve(serverId.value() + ".json");
    }

    private static List<McpTool> toDomain(List<CachedTool> cached) {
        List<McpTool> out = new ArrayList<>(cached.size());
        for (CachedTool tool : cached) {
            out.add(new McpTool(tool.name, tool.description,
                    tool.inputSchema == null ? Map.of() : tool.inputSchema));
        }
        return out;
    }

    /** On-disk shape. Public fields so Jackson can populate them. */
    static class CacheFile {
        public String fetchedAt;
        public List<CachedTool> tools;
    }

    static class CachedTool {
        public String name;
        public String description;
        public Map<String, Object> inputSchema;
    }
}
