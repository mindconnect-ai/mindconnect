package ai.mindconnect.agent.tools.gmail;

import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.mcp.proxy.McpConnection;
import ai.mindconnect.mcp.proxy.McpResult;
import ai.mindconnect.mcp.proxy.McpSessionRegistry;
import ai.mindconnect.mcp.proxy.McpStdioSpawn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Generic {@link Tool} bridge from an agent-facing tool name to an MCP
 * sub-tool. All metadata (name, description, schema) comes from the cached
 * MCP {@code tools/list} response — no hand-written wrappers per sub-tool.
 *
 * <p>Connection lifecycle is delegated to {@link McpSessionRegistry}: all
 * sub-tools of the same MCP provider share one container per session.
 *
 * <p>Lives in the gmail module for now — when a second MCP provider lands,
 * lift this into {@code mc-mcp-proxy} as a public class.
 */
final class McpToolAdapter implements Tool {

    private static final Logger log = LoggerFactory.getLogger(McpToolAdapter.class);

    private final String agentToolName;
    private final String subToolName;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final McpSessionRegistry registry;
    private final UUID sessionId;
    private final String providerKey;
    private final Supplier<McpStdioSpawn> spawnSupplier;

    McpToolAdapter(String agentToolName,
                   String subToolName,
                   String description,
                   Map<String, Object> inputSchema,
                   McpSessionRegistry registry,
                   UUID sessionId,
                   String providerKey,
                   Supplier<McpStdioSpawn> spawnSupplier) {
        this.agentToolName = agentToolName;
        this.subToolName = subToolName;
        this.description = description;
        this.inputSchema = inputSchema;
        this.registry = registry;
        this.sessionId = sessionId;
        this.providerKey = providerKey;
        this.spawnSupplier = spawnSupplier;
    }

    @Override public String name() { return agentToolName; }
    @Override public String description() { return description; }
    @Override public Map<String, Object> parametersSchema() { return inputSchema; }

    @Override public String execute(Map<String, Object> arguments) {
        try {
            McpConnection conn = registry.getOrOpen(sessionId, providerKey, spawnSupplier.get());
            McpResult result = conn.callTool(subToolName, arguments);
            if (result.isError()) {
                log.warn("{} → {} returned error: {}", agentToolName, subToolName, result.asString());
                return "Error from MCP server: " + result.asString();
            }
            String text = result.asString();
            // The Gmail MCP server returns an empty text part for "no matches".
            // Returning "" to the LLM gives it no signal and triggers wild
            // retries / hallucinated tool calls — surface the no-result state
            // explicitly so the LLM treats it as a normal outcome.
            if (text == null || text.isBlank()) {
                return "No results. The tool ran successfully but found nothing for the given arguments.";
            }
            return text;
        } catch (RuntimeException e) {
            log.error("{} → {} failed", agentToolName, subToolName, e);
            return "Tool execution failed: " + e.getMessage();
        }
    }
}
