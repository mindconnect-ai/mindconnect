package ai.mindconnect.mcp.proxy;

import ai.mindconnect.mcp.gateway.McpResult;
import ai.mindconnect.mcp.gateway.McpTool;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
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
 * <p>Both transports the SDK offers for talking to a server are here:
 * stdio for a process we start, streamable HTTP for one we do not. Which
 * one a call uses follows from the {@link McpEndpoint} handed in — nothing
 * else in the system has to know the difference.
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
    public List<McpTool> listTools(McpEndpoint endpoint) {
        try (McpConnection c = connect(endpoint)) {
            return c.listTools();
        }
    }

    @Override
    public McpResult callTool(McpEndpoint endpoint, String toolName, Map<String, Object> args) {
        try (McpConnection c = connect(endpoint)) {
            return c.callTool(toolName, args);
        }
    }

    @Override
    public McpConnection connect(McpEndpoint endpoint) {
        return switch (endpoint) {
            case McpStdioSpawn spawn -> connectStdio(spawn);
            case McpHttpEndpoint http -> connectHttp(http);
        };
    }

    private McpConnection connectStdio(McpStdioSpawn spawn) {
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

        return initialize(transport, spawn.callTimeout(), spawn.startupTimeout(),
                "process '" + spawn.command() + "'");
    }

    /**
     * The SDK's streamable-HTTP transport, which brings session handling
     * ({@code Mcp-Session-Id}) and stream resumption with it. Our part is the
     * URL split into base and path — the builder wants them separately — and
     * the headers on every request.
     */
    private McpConnection connectHttp(McpHttpEndpoint endpoint) {
        String path = endpoint.url().getRawPath() == null || endpoint.url().getRawPath().isBlank()
                ? "/"
                : endpoint.url().getRawPath();
        if (endpoint.url().getRawQuery() != null) {
            path = path + "?" + endpoint.url().getRawQuery();
        }

        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(endpoint.origin())
                .endpoint(path)
                .jsonMapper(jsonMapper)
                .connectTimeout(endpoint.connectTimeout())
                .customizeRequest(request ->
                        endpoint.headers().forEach(request::header))
                .build();

        return initialize(transport, endpoint.callTimeout(), endpoint.connectTimeout(),
                "endpoint " + endpoint.origin());
    }

    /**
     * The part both transports share: build the client, shake hands, and
     * leave nothing running if that fails.
     *
     * <p>{@code what} names the server in the error without repeating its
     * arguments or headers — those carry tokens.
     */
    private McpConnection initialize(McpClientTransport transport,
                                     java.time.Duration requestTimeout,
                                     java.time.Duration initializationTimeout,
                                     String what) {
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(requestTimeout)
                .initializationTimeout(initializationTimeout)
                .build();
        try {
            client.initialize();
        } catch (RuntimeException e) {
            client.closeGracefully();
            throw new McpProxyException(
                    "MCP initialize failed for " + what + ": " + e.getMessage(), e);
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
