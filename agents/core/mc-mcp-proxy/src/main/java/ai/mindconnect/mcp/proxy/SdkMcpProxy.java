package ai.mindconnect.mcp.proxy;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
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
}
