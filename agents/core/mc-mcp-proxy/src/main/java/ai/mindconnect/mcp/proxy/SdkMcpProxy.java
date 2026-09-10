package ai.mindconnect.mcp.proxy;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * {@link McpProxy} implementation on top of {@code io.modelcontextprotocol.sdk:mcp}.
 *
 * <p>Three flavours of usage are intended:
 * <ul>
 *   <li><b>One-shot</b> via {@link #callTool} / {@link #listTools}: spawn,
 *       initialize, single call, close. Convenient for ad-hoc CLI calls.</li>
 *   <li><b>Persistent</b> via {@link #connect}: caller owns the connection
 *       and is responsible for closing it. Useful for sequences of calls
 *       against the same server.</li>
 *   <li><b>Cached</b> via {@link McpSessionRegistry} on top of {@code connect()}:
 *       per-session reuse with idle eviction. This is what the agent runtime
 *       actually uses.</li>
 * </ul>
 */
public final class SdkMcpProxy implements McpProxy {

    private static final Logger log = LoggerFactory.getLogger(SdkMcpProxy.class);

    private final McpJsonMapper jsonMapper;

    public SdkMcpProxy() {
        this(new JacksonMcpJsonMapperSupplier().get());
    }

    public SdkMcpProxy(McpJsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public List<McpTool> listTools(McpStdioSpawn spawn) {
        try (McpConnection c = connect(spawn)) {
            return c.listTools();
        }
    }

    @Override
    public McpResult callTool(McpStdioSpawn spawn, String toolName, Map<String, Object> args) {
        try (McpConnection c = connect(spawn)) {
            return c.callTool(toolName, args);
        }
    }

    @Override
    public McpConnection connect(McpStdioSpawn spawn) {
        // A command that cannot be run fails here, in a sentence, rather than
        // in thirty seconds of initialization timeout with a dropped reactive
        // error in the log. The usual case is an MCP server behind docker on
        // a machine where docker is not installed or not running.
        requireExecutable(spawn.command());

        ServerParameters params = ServerParameters.builder(spawn.command())
                .args(spawn.args())
                .env(spawn.env())
                .build();

        StdioClientTransport transport = new StdioClientTransport(params, jsonMapper);
        transport.setStdErrorHandler(line ->
                log.warn("[mcp:{}] {}", spawn.command(), line));

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(spawn.callTimeout())
                .initializationTimeout(spawn.startupTimeout())
                .build();
        try {
            client.initialize();
        } catch (RuntimeException e) {
            client.closeGracefully();
            throw new McpProxyException("MCP initialize failed: " + e.getMessage(), e);
        }
        return new SdkMcpConnection(client, jsonMapper);
    }

    /**
     * Resolves a command the way the OS would before anything tries to run
     * it: a name with a path separator must be an executable file, a bare
     * name must be findable on {@code PATH}. A broken symlink counts as
     * missing — which is exactly what a docker link left behind by an
     * uninstalled Docker Desktop is.
     *
     * @throws McpProxyException when nothing executable answers to the name
     */
    static void requireExecutable(String command) {
        Path direct = Path.of(command);
        if (direct.getNameCount() > 1 || command.startsWith("/")) {
            if (Files.isExecutable(direct)) return;
            throw new McpProxyException("MCP server command is not executable: " + command);
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) continue;
                if (Files.isExecutable(Path.of(dir, command))) return;
            }
        }
        throw new McpProxyException("MCP server command not found on PATH: " + command
                + " — install it, or start the service that provides it");
    }
}
