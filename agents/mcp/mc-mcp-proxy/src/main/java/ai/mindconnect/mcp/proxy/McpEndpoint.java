package ai.mindconnect.mcp.proxy;

/**
 * Where an MCP server is and how to talk to it, at wire level: either a
 * process this JVM starts and speaks stdio with, or an HTTP endpoint
 * somebody else runs.
 *
 * <p>Sealed so the proxy's transport switch is exhaustive by construction —
 * a third transport cannot be added without every place that dispatches on
 * one failing to compile.
 *
 * <p>Note what is <em>not</em> here: no server id, no display name, no
 * credential reference. This is the connection, not the registration —
 * {@code McpServerRegistration} in the gateway module is the latter.
 */
public sealed interface McpEndpoint permits McpStdioSpawn, McpHttpEndpoint {
}
