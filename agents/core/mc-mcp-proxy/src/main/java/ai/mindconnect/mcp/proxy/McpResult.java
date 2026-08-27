package ai.mindconnect.mcp.proxy;

import java.util.List;

/**
 * Result of an MCP {@code tools/call}.
 *
 * <p>v0 vereinfacht: nur Text-Content. Image/Resource-Content kommt mit
 * der vollen mcp-proxy-Iteration (siehe concept §4.2).
 *
 * @param isError  true wenn der Server einen Error returned hat
 * @param textParts  concatenierte Text-Content-Items in Reihenfolge
 */
public record McpResult(boolean isError, List<String> textParts) {

    public McpResult {
        textParts = textParts == null ? List.of() : List.copyOf(textParts);
    }

    /** Convenience: join all text parts with newlines. */
    public String asString() {
        return String.join("\n", textParts);
    }
}
