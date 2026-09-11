package ai.mindconnect.mcp.gateway;

import java.time.Instant;
import java.util.List;

/**
 * What is currently known about a server's tools, and when it was learned.
 *
 * <p>Discovery costs a server start, so the answer is remembered rather than
 * asked again — which means it can be old. The timestamp is the point: it is
 * what tells an operator whether re-reading is worth a click, and without it
 * a tool list is a claim without a date.
 *
 * @param fetchedAt  when the server was last asked; null when never
 * @param tools      what it said then; empty when it has not been asked or
 *                   could not be reached
 */
public record McpDiscovery(Instant fetchedAt, List<McpTool> tools) {

    public McpDiscovery {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public static McpDiscovery never() {
        return new McpDiscovery(null, List.of());
    }
}
