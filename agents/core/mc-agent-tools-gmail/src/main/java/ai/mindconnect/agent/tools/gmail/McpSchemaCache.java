package ai.mindconnect.agent.tools.gmail;

import ai.mindconnect.mcp.proxy.McpConnection;
import ai.mindconnect.mcp.proxy.McpProxy;
import ai.mindconnect.mcp.proxy.McpStdioSpawn;
import ai.mindconnect.mcp.proxy.McpTool;
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
 * Disk-backed cache for an MCP server's {@code tools/list} response.
 *
 * <p>First time the provider binds, we spawn the server once to discover
 * its tool catalog and persist the result. Every subsequent startup
 * (including app restarts) skips the spawn and loads from disk.
 *
 * <p>Invalidation is manual — delete the file. There's no TTL or image-tag
 * check. If the MCP server gets new tools, the operator deletes the cache
 * file and they appear after the next restart.
 *
 * <p>v0 lives in the gmail module. Will be promoted to {@code mc-mcp-proxy}
 * when a second MCP provider arrives.
 */
final class McpSchemaCache {

    private static final Logger log = LoggerFactory.getLogger(McpSchemaCache.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path cacheFile;

    McpSchemaCache(Path cacheFile) {
        this.cacheFile = cacheFile;
    }

    /**
     * Load schemas from disk; if missing, spawn the server (one-shot via
     * proxy) to discover them and persist.
     */
    List<McpTool> loadOrFetch(McpProxy proxy, McpStdioSpawn spawn) throws IOException {
        if (Files.isRegularFile(cacheFile)) {
            try {
                CacheFile loaded = MAPPER.readValue(cacheFile.toFile(), CacheFile.class);
                if (loaded.tools != null && !loaded.tools.isEmpty()) {
                    log.info("MCP schema cache: loaded {} tool(s) from {}",
                            loaded.tools.size(), cacheFile);
                    return toDomain(loaded.tools);
                }
                log.warn("MCP schema cache file {} was empty — refetching", cacheFile);
            } catch (IOException e) {
                log.warn("MCP schema cache file {} unreadable ({}) — refetching",
                        cacheFile, e.toString());
            }
        }
        List<McpTool> fresh = fetch(proxy, spawn);
        write(fresh);
        return fresh;
    }

    private List<McpTool> fetch(McpProxy proxy, McpStdioSpawn spawn) {
        log.info("MCP schema cache: fetching tools/list (one-shot spawn)");
        try (McpConnection c = proxy.connect(spawn)) {
            return c.listTools();
        }
    }

    private void write(List<McpTool> tools) throws IOException {
        Files.createDirectories(cacheFile.getParent());
        CacheFile file = new CacheFile();
        file.fetchedAt = Instant.now().toString();
        file.tools = new ArrayList<>(tools.size());
        for (McpTool t : tools) {
            CachedTool ct = new CachedTool();
            ct.name = t.name();
            ct.description = t.description();
            ct.inputSchema = t.inputSchema();
            file.tools.add(ct);
        }
        Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
        MAPPER.writeValue(tmp.toFile(), file);
        Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        log.info("MCP schema cache: wrote {} tool(s) to {}", tools.size(), cacheFile);
    }

    private static List<McpTool> toDomain(List<CachedTool> cached) {
        List<McpTool> out = new ArrayList<>(cached.size());
        for (CachedTool c : cached) {
            out.add(new McpTool(c.name, c.description,
                    c.inputSchema == null ? Map.of() : c.inputSchema));
        }
        return out;
    }

    /** Top-level JSON shape on disk. Public-default so Jackson can populate it. */
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
