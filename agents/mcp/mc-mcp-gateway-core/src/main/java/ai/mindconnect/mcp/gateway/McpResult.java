package ai.mindconnect.mcp.gateway;

import java.util.List;

/**
 * Result of an MCP {@code tools/call}.
 *
 * <p>Text only for now. Image and resource content is dropped one layer
 * below, in the proxy — see concept 21 §4.1 (E5): making it visible here
 * is a separate step, and it needs a size policy before it can cross a
 * remote gateway.
 *
 * @param isError    the server answered with an error result
 * @param textParts  text content items, in the order the server sent them
 */
public record McpResult(boolean isError, List<String> textParts) {

    public McpResult {
        textParts = textParts == null ? List.of() : List.copyOf(textParts);
    }

    /** All text parts joined with newlines. */
    public String asString() {
        return String.join("\n", textParts);
    }
}
