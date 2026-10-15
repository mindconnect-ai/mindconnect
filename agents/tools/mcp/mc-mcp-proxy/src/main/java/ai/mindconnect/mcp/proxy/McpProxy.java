package ai.mindconnect.mcp.proxy;

import ai.mindconnect.mcp.gateway.McpResult;
import ai.mindconnect.mcp.gateway.McpTool;

import java.util.List;
import java.util.Map;

/**
 * Thin facade over the official MCP Java SDK.
 *
 * <p>Scope:
 * <ul>
 *   <li>Two transports, chosen by the {@link McpEndpoint} handed in:
 *       {@code stdio} for a container or process this JVM starts,
 *       streamable HTTP for a server somebody else runs.</li>
 *   <li>One-shot semantics for {@link #listTools} and {@link #callTool}:
 *       connect, initialize, single call, tear down. {@link #connect} and
 *       the {@code McpSessionRegistry} on top of it are for sequences.</li>
 *   <li>The caller brings finished values — env entries, headers, tokens.
 *       The proxy resolves nothing and has no credentials dependency.</li>
 * </ul>
 *
 * <p>This layer is deliberately thin so that an eventual major bump of the
 * SDK hits only here — callers see only {@link McpProxy},
 * {@link McpTool}, {@link McpResult}.
 */
public interface McpProxy {

    /**
     * Connect to the server described by {@code endpoint}, run
     * {@code tools/list}, tear down. One-shot.
     */
    List<McpTool> listTools(McpEndpoint endpoint);

    /**
     * Connect, call {@code tools/call} with {@code toolName + args}, return
     * the result, tear down. One-shot.
     */
    McpResult callTool(McpEndpoint endpoint, String toolName, Map<String, Object> args);

    /**
     * Connect and return a persistent {@link McpConnection} the caller can
     * use for multiple subsequent calls. Caller owns the lifecycle
     * (try-with-resources or explicit {@link McpConnection#close()}).
     *
     * <p>Use this when several tool calls in a row go to the same server: it
     * avoids a container start per call over stdio, and a handshake per call
     * over HTTP. For one-off calls prefer
     * {@link #callTool(McpEndpoint, String, Map)}.
     */
    McpConnection connect(McpEndpoint endpoint);
}
