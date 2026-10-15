package ai.mindconnect.mcp.gateway;

import java.util.List;

/**
 * Outcome of trying a registration out before saving it: either the server
 * answered and told us its tools, or it did not and said why.
 *
 * @param ok        the server completed the handshake and listed its tools
 * @param message   what happened, in a sentence an operator can act on
 * @param tools     what it offers; empty when {@code ok} is false
 * @param durationMs how long the attempt took — a slow success is worth seeing
 */
public record McpProbeResult(boolean ok, String message, List<McpTool> tools, long durationMs) {

    public McpProbeResult {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public static McpProbeResult success(List<McpTool> tools, long durationMs) {
        return new McpProbeResult(true, "Connected — " + tools.size() + " tool(s) discovered.",
                tools, durationMs);
    }

    public static McpProbeResult failure(String message, long durationMs) {
        return new McpProbeResult(false, message, List.of(), durationMs);
    }
}
