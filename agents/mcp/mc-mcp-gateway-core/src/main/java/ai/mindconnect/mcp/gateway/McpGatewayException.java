package ai.mindconnect.mcp.gateway;

/** An MCP server could not be reached, started, or answered unusably. */
public class McpGatewayException extends RuntimeException {

    public McpGatewayException(String message) {
        super(message);
    }

    public McpGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
