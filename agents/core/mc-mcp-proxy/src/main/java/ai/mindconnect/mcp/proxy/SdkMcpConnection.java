package ai.mindconnect.mcp.proxy;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link McpConnection} backed by an initialized {@link McpSyncClient} from
 * the official MCP Java SDK.
 *
 * <p>Created exclusively by {@link SdkMcpProxy} which owns the spawn +
 * initialize sequence. Once handed to the caller this object is the only
 * thing holding the underlying process alive — call {@link #close()} or the
 * process leaks.
 */
final class SdkMcpConnection implements McpConnection {

    private static final Logger log = LoggerFactory.getLogger(SdkMcpConnection.class);

    private final McpSyncClient client;
    private final McpJsonMapper jsonMapper;
    private volatile boolean closed;

    SdkMcpConnection(McpSyncClient client, McpJsonMapper jsonMapper) {
        this.client = client;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public List<McpTool> listTools() {
        McpSchema.ListToolsResult result = client.listTools();
        List<McpTool> tools = new ArrayList<>(result.tools().size());
        for (McpSchema.Tool t : result.tools()) {
            tools.add(new McpTool(
                    t.name(),
                    t.description(),
                    t.inputSchema() == null ? Map.of() :
                            jsonMapper.convertValue(t.inputSchema(), Map.class)
            ));
        }
        return tools;
    }

    @Override
    public McpResult callTool(String toolName, Map<String, Object> args) {
        McpSchema.CallToolRequest req = new McpSchema.CallToolRequest(
                toolName,
                args == null ? Map.of() : args
        );
        McpSchema.CallToolResult result = client.callTool(req);
        List<String> textParts = new ArrayList<>();
        if (result.content() != null) {
            for (McpSchema.Content item : result.content()) {
                if (item instanceof McpSchema.TextContent tc) {
                    textParts.add(tc.text());
                }
                // Image/Resource content ignored in v0 — see McpResult javadoc
            }
        }
        boolean isError = Boolean.TRUE.equals(result.isError());
        return new McpResult(isError, textParts);
    }

    @Override
    public boolean isHealthy() {
        return !closed && client.isInitialized();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            client.closeGracefully();
        } catch (RuntimeException e) {
            log.warn("McpSyncClient.closeGracefully failed: {}", e.toString());
        }
    }
}
