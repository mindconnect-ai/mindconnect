package ai.mindconnect.mcp.proxy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Self-contained description of a stdio-based MCP server spawn.
 *
 * <p>Caller-built — the proxy interprets the fields directly, no
 * indirection through McpServerDef etc. (that comes later in
 * {@code mc-tool-registry}).
 *
 * @param command   executable to run (e.g. "docker")
 * @param args      arguments (e.g. ["run", "-i", "--rm", "-v", "...", "image"])
 * @param env       env vars for the spawn — already resolved, the caller
 *                  has e.g. substituted OAuth tokens
 * @param startupTimeout how long to wait for {@code initialize} handshake
 * @param callTimeout    per-request timeout for {@code tools/call}
 */
public record McpStdioSpawn(
        String command,
        List<String> args,
        Map<String, String> env,
        Duration startupTimeout,
        Duration callTimeout
) {

    public McpStdioSpawn {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command required");
        }
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        if (startupTimeout == null) startupTimeout = Duration.ofSeconds(30);
        if (callTimeout == null) callTimeout = Duration.ofSeconds(60);
    }
}
