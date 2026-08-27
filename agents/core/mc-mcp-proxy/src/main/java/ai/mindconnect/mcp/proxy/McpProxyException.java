package ai.mindconnect.mcp.proxy;

/** Runtime failure during MCP spawn/initialize/call. Always wraps a cause. */
public class McpProxyException extends RuntimeException {
    public McpProxyException(String message, Throwable cause) {
        super(message, cause);
    }
}
