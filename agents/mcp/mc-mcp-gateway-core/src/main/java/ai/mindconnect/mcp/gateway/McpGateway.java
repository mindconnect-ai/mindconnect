package ai.mindconnect.mcp.gateway;

import java.util.List;
import java.util.Map;

/**
 * Access to the MCP servers of this installation: which ones there are,
 * what they can do, and how to call them.
 *
 * <p>The port exists so that "the MCP machinery" can live in this JVM or on
 * a server of its own without the caller noticing. Everything it hands out
 * is data — no connections, no handles, no live objects. That is what
 * keeps an HTTP implementation possible (concept 21 §5.2).
 *
 * <p>Like every store, a gateway serves the one namespace its process is
 * bound to; nothing here names it.
 *
 * <h2>Cost</h2>
 * {@link #servers()} and {@link #tools(McpServerId)} answer a catalog question
 * and are called often — an implementation caches them and does not spawn a
 * server per call. {@link #call} is the expensive one; the connection
 * behind it is pooled per session, which is why {@link #release} exists.
 *
 * <h2>Failures</h2>
 * An unreachable server must not take the catalog down: {@link #servers()}
 * lists it, {@link #tools(McpServerId)} returns an empty list, and only
 * {@link #call} fails — with {@link McpGatewayException} for transport
 * trouble, and with a normal result carrying {@link McpResult#isError()}
 * when the server itself rejected the call.
 */
public interface McpGateway {

    /**
     * Every enabled registration. Cheap and side-effect free — no server is
     * contacted.
     *
     * <p>A per-user visibility filter would narrow this further and is not
     * here yet.
     */
    List<McpServerInfo> servers();

    /**
     * The sub-tools of one server, discovered from the server itself and
     * cached. Empty when the server is unknown, disabled or unreachable — a
     * catalog must render either way.
     */
    List<McpTool> tools(McpServerId server);

    /**
     * Changes whenever the set of servers or their tools might have — a
     * saved registration, a deleted one, a dropped discovery cache.
     *
     * <p>It exists so a caller can hold its own view of the catalog and still
     * notice a change without asking for the whole thing: the tool registry
     * asks its provider for names on every lookup, and comparing a long is the
     * only thing cheap enough to do there. An implementation must answer it
     * without contacting any MCP server.
     */
    long catalogVersion();

    /**
     * Runs one tool of one server on behalf of {@code caller}.
     *
     * @throws McpGatewayException when the server cannot be reached or the
     *         call breaks down; a rejection by the server itself comes back
     *         as a result with {@link McpResult#isError()} set.
     */
    McpResult call(McpCaller caller, McpServerId server, String toolName, Map<String, Object> arguments);

    /**
     * Releases everything held for this caller's session — connections,
     * containers. Idempotent, and safe to call for a session that never used
     * a tool.
     *
     * <p>Nobody calls this yet: the runtime has no "session over" hook
     * (concept 21, E4). Until it does, implementations must fall back on an
     * idle timeout, or containers outlive the chats that started them.
     */
    void release(McpCaller caller);
}
