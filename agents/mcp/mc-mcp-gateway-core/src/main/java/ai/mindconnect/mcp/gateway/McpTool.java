package ai.mindconnect.mcp.gateway;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Metadata of a tool exposed by an MCP server (one entry of a
 * {@code tools/list} response).
 *
 * @param name         server-side tool name, without any prefix
 * @param description  human-readable description, straight from the server
 * @param inputSchema  JSON-Schema for the call arguments — an opaque map,
 *                     handed to the LLM as-is
 */
public record McpTool(
        String name,
        String description,
        Map<String, Object> inputSchema
) {
    public McpTool {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name required");
        }
        // Key order is kept deliberately: this schema is serialized into the
        // LLM request, and Map.copyOf would shuffle it per JVM run.
        inputSchema = inputSchema == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
    }
}
