package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.mcp.gateway.McpCatalog;
import ai.mindconnect.mcp.gateway.McpCatalogEntry;
import ai.mindconnect.mcp.gateway.McpTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Docker's catalog of container MCP servers, as one document.
 *
 * <p>Each entry carries the image, an icon, the source repository, the
 * secrets the server expects — and the tool names, which means a catalog
 * search can say what a server offers <em>without starting it</em>.
 *
 * <p>Two things to know about the source. It is the document Docker Desktop
 * itself fetches, not a documented API, so it can change shape or move
 * without notice — hence one parser, defensive throughout, and a failure
 * that costs the search results and nothing else. And it is Docker-shaped:
 * npm- and pypi-based servers are not in it, and neither are remote HTTP
 * ones. A second catalog against the official MCP registry would cover
 * those; the port is the same.
 */
public final class DockerMcpCatalog implements McpCatalog {

    private static final Logger log = LoggerFactory.getLogger(DockerMcpCatalog.class);

    public static final String DEFAULT_URL = "https://desktop.docker.com/mcp/catalog/v2/catalog.yaml";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final URI url;
    private final Duration ttl;
    private final HttpClient http;

    private volatile List<McpCatalogEntry> cached = List.of();
    private volatile Instant fetchedAt = Instant.EPOCH;

    public DockerMcpCatalog(String url, Duration ttl, Duration timeout) {
        this.url = URI.create(url == null || url.isBlank() ? DEFAULT_URL : url);
        this.ttl = ttl == null ? Duration.ofHours(6) : ttl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout == null ? Duration.ofSeconds(10) : timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return "Docker MCP Catalog";
    }

    @Override
    public List<McpCatalogEntry> search(String query, int limit) {
        List<McpCatalogEntry> all = entries();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return all.stream()
                .filter(e -> needle.isEmpty() || matches(e, needle))
                .sorted(Comparator.comparing(McpCatalogEntry::title, String.CASE_INSENSITIVE_ORDER))
                .limit(Math.max(1, limit))
                .toList();
    }

    private static boolean matches(McpCatalogEntry entry, String needle) {
        return entry.id().toLowerCase(Locale.ROOT).contains(needle)
                || entry.title().toLowerCase(Locale.ROOT).contains(needle)
                || (entry.description() != null
                    && entry.description().toLowerCase(Locale.ROOT).contains(needle));
    }

    /** The catalog, fetched at most once per TTL. Stale beats empty on failure. */
    private List<McpCatalogEntry> entries() {
        if (Instant.now().isBefore(fetchedAt.plus(ttl)) && !cached.isEmpty()) {
            return cached;
        }
        synchronized (this) {
            if (Instant.now().isBefore(fetchedAt.plus(ttl)) && !cached.isEmpty()) {
                return cached;
            }
            try {
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder(url).GET().timeout(Duration.ofSeconds(30)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    log.warn("MCP catalog {} answered HTTP {}", url, response.statusCode());
                    return cached;
                }
                List<McpCatalogEntry> parsed = parse(response.body());
                cached = parsed;
                fetchedAt = Instant.now();
                log.info("MCP catalog: {} entries from {}", parsed.size(), url);
                return parsed;
            } catch (InterruptedException e) {
                // The one failure the flag belongs to. It used to be set for
                // every failure, so an unreachable catalog host left the
                // request thread flagged, it went back into the pool that
                // way, and some later, unrelated request died of an
                // interruption that had nothing to do with it.
                Thread.currentThread().interrupt();
                log.warn("MCP catalog {}: interrupted while fetching", url);
                return cached;
            } catch (Exception e) {
                // A catalog is a convenience. Losing it must not cost more
                // than the suggestions it would have made.
                log.warn("MCP catalog {} unavailable: {}", url, e.toString());
                return cached;
            }
        }
    }

    /** Package-private for the test: the parser is the part with rules in it. */
    static List<McpCatalogEntry> parse(String yaml) throws java.io.IOException {
        JsonNode root = YAML.readTree(yaml);
        JsonNode registry = root.path("registry").isObject() ? root.get("registry") : root;
        List<McpCatalogEntry> out = new ArrayList<>();
        registry.fields().forEachRemaining(field -> {
            McpCatalogEntry entry = entry(field.getKey(), field.getValue());
            if (entry != null) {
                out.add(entry);
            }
        });
        return List.copyOf(out);
    }

    /** Null for an entry we cannot run — one without an image, typically a remote server. */
    private static McpCatalogEntry entry(String id, JsonNode node) {
        String image = node.path("image").asText(null);
        if (image == null || image.isBlank()) {
            return null;
        }
        Map<String, String> env = new LinkedHashMap<>();
        List<McpCatalogEntry.RequiredValue> required = new ArrayList<>();
        for (JsonNode secret : node.path("secrets")) {
            String envName = secret.path("env").asText(null);
            if (envName == null || envName.isBlank()) {
                continue;
            }
            // Prefilled with its own placeholder, not left empty: the
            // requirement is visible either way, but this way the path of
            // least resistance puts the secret in the environment instead of
            // into the registration file. An operator who would rather paste
            // the value overwrites it; one who does nothing gets a precise
            // "variable not set" at Test connection rather than a puzzling
            // 401 from the server.
            env.put(envName, "${" + envName + "}");
            required.add(new McpCatalogEntry.RequiredValue(envName,
                    secret.path("description").asText(null), true));
        }
        List<String> tools = new ArrayList<>();
        for (JsonNode tool : node.path("tools")) {
            String name = tool.path("name").asText(null);
            if (name != null && !name.isBlank()) {
                tools.add(name);
            }
        }
        return new McpCatalogEntry(
                id,
                node.path("title").asText(id),
                node.path("description").asText(null),
                node.path("icon").asText(null),
                node.path("source").asText(null),
                new McpTarget.Docker(image, List.of(), env, List.of(), List.of()),
                tools,
                required);
    }
}
