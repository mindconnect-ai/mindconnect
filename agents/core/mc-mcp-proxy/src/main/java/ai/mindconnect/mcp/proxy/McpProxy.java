package ai.mindconnect.mcp.proxy;

import java.util.List;
import java.util.Map;

/**
 * Thin facade over the official MCP Java SDK.
 *
 * <p>v0 scope (phase 0 — walking skeleton):
 * <ul>
 *   <li>{@code stdio} transport only (Docker/Process). HTTP-streamable comes later.</li>
 *   <li>One-shot semantics: every call spawns + initializes + tears down the
 *       MCP server. No connection caching. Phase 1+ builds the
 *       {@code McpSessionRegistry} on top.</li>
 *   <li>The caller brings the finished env map (token values etc.). The proxy
 *       resolves nothing — it has no credentials dependency.</li>
 * </ul>
 *
 * <p>This layer is deliberately thin so that an eventual major bump of the
 * SDK hits only here — callers see only {@link McpProxy},
 * {@link McpTool}, {@link McpResult}.
 */
public interface McpProxy {

    /**
     * Spawn the server described by {@code spawn}, run {@code tools/list},
     * tear down. One-shot.
     */
    List<McpTool> listTools(McpStdioSpawn spawn);

    /**
     * Spawn the server, call {@code tools/call} with {@code toolName + args},
     * return the result, tear down. One-shot.
     */
    McpResult callTool(McpStdioSpawn spawn, String toolName, Map<String, Object> args);

    /**
     * Spawn the server and return a persistent {@link McpConnection} the
     * caller can use for multiple subsequent calls. Caller owns the lifecycle
     * (try-with-resources or explicit {@link McpConnection#close()}).
     *
     * <p>Use this when several tool calls in a row will use the same server —
     * it avoids the per-call container spawn overhead. For one-off calls
     * prefer {@link #callTool(McpStdioSpawn, String, Map)}.
     */
    McpConnection connect(McpStdioSpawn spawn);
}
