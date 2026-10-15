package ai.mindconnect.mcp.gateway;

/**
 * What a caller may know about a registered MCP server: enough to list it
 * and to build tool names from it, and nothing about how it is started or
 * which credentials it uses.
 *
 * @param id              stable identifier of the registration
 * @param displayName     name for humans
 * @param description     what this server is for; may be null
 * @param toolNamePrefix  namespace of its sub-tools in the agent's tool list
 */
public record McpServerInfo(
        McpServerId id,
        String displayName,
        String description,
        String toolNamePrefix
) {
}
