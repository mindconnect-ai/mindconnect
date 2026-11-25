package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.mcp.gateway.McpDiscovery;
import ai.mindconnect.mcp.gateway.McpTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The stored form of a {@link McpDiscovery}, shared by every
 * {@link McpDiscoveryStore} so that a cache file and a cache row hold the same
 * document. Hand-written for the reason {@link McpRegistrationJson} is:
 * {@code mc-mcp-gateway-core} stays free of Jackson.
 *
 * <p>Shape:
 * <pre>{@code
 * {
 *   "fetchedAt": "2026-09-23T10:15:30Z",
 *   "tools": [ { "name": "search_emails", "description": "...", "inputSchema": { ... } } ]
 * }
 * }</pre>
 * Fields it does not know are ignored, so a store may keep more beside them.
 */
public final class McpDiscoveryJson {

    private static final TypeReference<LinkedHashMap<String, Object>> SCHEMA = new TypeReference<>() { };

    private final ObjectMapper mapper;

    public McpDiscoveryJson(JsonMapperFactory json) {
        this.mapper = json.create();
    }

    /** The JSON form of {@code discovery}, in the shape {@link #read} expects. */
    public ObjectNode write(McpDiscovery discovery, JsonNodeFactory nodes) {
        ObjectNode root = nodes.objectNode();
        root.put("fetchedAt", discovery.fetchedAt() == null ? null : discovery.fetchedAt().toString());
        ArrayNode tools = root.putArray("tools");
        for (McpTool tool : discovery.tools()) {
            ObjectNode t = tools.addObject();
            t.put("name", tool.name());
            t.put("description", tool.description());
            t.set("inputSchema", mapper.valueToTree(tool.inputSchema()));
        }
        return root;
    }

    /**
     * Reads what {@link #write} wrote.
     *
     * @throws IllegalArgumentException when a tool has no name or the timestamp is unreadable
     */
    public McpDiscovery read(JsonNode node) {
        Instant fetchedAt = node.hasNonNull("fetchedAt") ? Instant.parse(node.get("fetchedAt").asText()) : null;
        List<McpTool> tools = new ArrayList<>();
        for (JsonNode t : node.path("tools")) {
            JsonNode schema = t.get("inputSchema");
            Map<String, Object> inputSchema = schema == null || !schema.isObject()
                    ? Map.of()
                    : mapper.convertValue(schema, SCHEMA);
            tools.add(new McpTool(t.path("name").asText(null), t.path("description").asText(null), inputSchema));
        }
        return new McpDiscovery(fetchedAt, tools);
    }
}
