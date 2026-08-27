package ai.mindconnect.mcp.proxy;

import java.util.List;
import java.util.Map;

/**
 * Persistent handle to a running MCP server process. Multiple {@code callTool}
 * / {@code listTools} calls are dispatched over the same stdio connection
 * without re-spawning.
 *
 * <p>Callers own the lifecycle: obtain a connection via
 * {@link McpProxy#connect(McpStdioSpawn)} or — more typically — via
 * {@link McpSessionRegistry#getOrOpen} (which adds caching and idle eviction),
 * and {@link #close()} when done. Closing an already-closed connection is
 * idempotent.
 *
 * <p>{@link #isHealthy()} is a cheap, best-effort probe: it does <em>not</em>
 * ping the server, it only reports whether the local handle still believes
 * the underlying transport is usable. A {@code false} means "open a new
 * connection"; calls on an unhealthy handle will likely fail.
 */
public interface McpConnection extends AutoCloseable {

    List<McpTool> listTools();

    McpResult callTool(String toolName, Map<String, Object> args);

    boolean isHealthy();

    @Override
    void close();
}
