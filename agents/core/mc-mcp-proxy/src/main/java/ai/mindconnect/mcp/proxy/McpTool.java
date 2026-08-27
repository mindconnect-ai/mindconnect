package ai.mindconnect.mcp.proxy;

import java.util.Map;

/**
 * Metadata of a tool exposed by an MCP server (result of {@code tools/list}).
 *
 * @param name         server-side tool name (without any prefix)
 * @param description  human-readable description
 * @param inputSchema  JSON-Schema for the call arguments — opaque map, the
 *                     LLM-side serializes it as-is
 */
public record McpTool(
        String name,
        String description,
        Map<String, Object> inputSchema
) {
}
