package ai.mindconnect.agent.tools.mcp;

import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.mcp.gateway.McpCaller;
import ai.mindconnect.mcp.gateway.McpGateway;
import ai.mindconnect.mcp.gateway.McpResult;
import ai.mindconnect.mcp.gateway.McpServerId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * One agent tool standing for one sub-tool of one MCP server. Name,
 * description and schema all come from the server's own {@code tools/list} —
 * there is no hand-written wrapper per sub-tool, and none should be added.
 *
 * <p>Grown out of the gmail module's adapter; the connection handling that
 * used to sit here now lives behind {@link McpGateway}.
 *
 * <p>Built without demanding a session: the tool catalog resolves a tool
 * with no user and no session just to read its description and schema, and
 * a metadata question must not require the means to make a call. The caller
 * identity is assembled when there is actually something to call.
 *
 * <p>Every failure comes back as text starting with {@code Error:}. That is
 * the prefix the tool-call worker, the chat's call history and the workflow
 * tool step read a failure by; any other wording counts as a result, so the
 * chat would show the call as successful and a workflow step would carry on
 * with the complaint as its output.
 */
final class McpToolAdapter implements Tool {

    private static final Logger log = LoggerFactory.getLogger(McpToolAdapter.class);

    private final McpGateway gateway;
    private final ToolCallScope scope;
    private final McpServerId serverId;
    private final String agentToolName;
    private final String subToolName;
    private final String description;
    private final Map<String, Object> inputSchema;

    McpToolAdapter(McpGateway gateway,
                   ToolCallScope scope,
                   McpServerId serverId,
                   String agentToolName,
                   String subToolName,
                   String description,
                   Map<String, Object> inputSchema) {
        this.gateway = gateway;
        this.scope = scope;
        this.serverId = serverId;
        this.agentToolName = agentToolName;
        this.subToolName = subToolName;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    @Override public String name() { return agentToolName; }

    @Override public String description() { return description; }

    @Override public Map<String, Object> parametersSchema() { return inputSchema; }

    @Override
    public String execute(Map<String, Object> arguments) {
        if (scope.sessionId() == null) {
            // Resolved outside an agent session. The catalog does that and never
            // calls — but so does a workflow started from the workflow admin or
            // the REST API, and that one does. Connections are held per session,
            // so there is nothing to call through.
            return "Error: " + agentToolName + " needs an agent session — an MCP tool runs in a chat "
                    + "or in a workflow started from one, not in a run started for nobody in particular.";
        }
        McpCaller caller = new McpCaller(scope.userId(), scope.sessionId());
        try {
            McpResult result = gateway.call(caller, serverId, subToolName, arguments);
            if (result.isError()) {
                log.warn("{} → {}/{} returned an error: {}",
                        agentToolName, serverId, subToolName, result.asString());
                return "Error: the MCP server reported: " + result.asString();
            }
            String text = result.asString();
            // An MCP server may answer "nothing found" with an empty text
            // part. Handing "" to the LLM gives it no signal and invites
            // retries and invented calls — name the empty outcome instead.
            if (text == null || text.isBlank()) {
                return "No results. The tool ran successfully but found nothing for the given arguments.";
            }
            return text;
        } catch (RuntimeException e) {
            log.error("{} → {}/{} failed", agentToolName, serverId, subToolName, e);
            return "Error: " + e.getMessage();
        }
    }
}
