package ai.mindconnect.adminui.service;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * One-shot execution of an {@link AgentTool} with admin-supplied JSON
 * arguments. Powers the "Test" button on the tool-detail page so an
 * admin can verify a tool's wiring without going through an agent.
 *
 * <p>Each test runs in a synthetic session (a random {@code sessionId},
 * {@code userId = "admin-test"}). Session-scoped tools
 * (todo_write, workspace_write, …) therefore write into an isolated
 * scratch session that's never reused — no risk of polluting a real
 * conversation. The session directory is left on disk for inspection;
 * the admin can clean it up later if it accumulates. What a tool holds for
 * the session in memory — an MCP server's pooled connection and the
 * container behind it — is released as soon as the test is over.
 */
@Service
public class ToolTestService {

    private static final Logger log = LoggerFactory.getLogger(ToolTestService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEST_USER_ID = "admin-test";

    private final ToolRegistry toolRegistry;

    public ToolTestService(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Parses {@code argumentsJson} as a {@code Map<String,Object>} and
     * invokes the tool. {@code argumentsJson == null || ""} is treated as
     * "no arguments" ({@code {}}). Returns the full execution outcome —
     * the UI renders both success and failure shapes from the same record.
     */
    public Result test(AgentDefinition agent, AgentTool agentTool, String argumentsJson) {
        return test(agentTool, argumentsJson);
    }

    /**
     * Tests a tool without an agent context — used by the top-level tool
     * catalog, where there is no owning agent. This is the real
     * implementation; the agent-based overload delegates here.
     */
    public Result test(AgentTool agentTool, String argumentsJson) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> args;
        try {
            args = parseArgs(argumentsJson);
        } catch (Exception e) {
            return Result.error("Invalid JSON arguments: " + e.getMessage(),
                    System.currentTimeMillis() - t0);
        }

        SessionId session = SessionId.random();
        var resolved = toolRegistry.resolve(agentTool, new ToolCallScope(UserId.of(TEST_USER_ID), session, null));
        if (resolved.isEmpty()) {
            return Result.error("Tool '" + agentTool.name()
                    + "' could not be resolved (missing factory or unavailable)?",
                    System.currentTimeMillis() - t0);
        }

        Tool tool = resolved.get();
        try {
            String text = tool.execute(args);
            long durMs = System.currentTimeMillis() - t0;
            log.info("Tool test '{}' OK in {} ms ({} chars output)",
                    agentTool.name(), durMs, text == null ? 0 : text.length());
            return Result.ok(text == null ? "" : text, durMs);
        } catch (Exception e) {
            long durMs = System.currentTimeMillis() - t0;
            log.warn("Tool test '{}' failed after {} ms: {}",
                    agentTool.name(), durMs, e.getMessage());
            return Result.error(e.getClass().getSimpleName() + ": " + e.getMessage(), durMs);
        } finally {
            // Nobody comes back to this session. What a tool holds for it — an
            // MCP server's pooled connection, the container behind it — would
            // otherwise stay until an idle timeout, once per click.
            toolRegistry.releaseSession(session);
        }
    }

    /**
     * Empty / whitespace-only input becomes the empty argument map (some
     * tools have no required args). Anything else must parse as a JSON
     * object — bare arrays / scalars are rejected to match the
     * {@code Tool.execute(Map)} contract.
     */
    private static Map<String, Object> parseArgs(String json) throws Exception {
        if (json == null || json.isBlank()) return Map.of();
        var parsed = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        return parsed == null ? Map.of() : parsed;
    }

    public record Result(
            boolean ok,
            String text,
            String errorMessage,
            long durationMs
    ) {
        public static Result ok(String text, long ms) { return new Result(true, text, null, ms); }
        public static Result error(String err, long ms) { return new Result(false, null, err, ms); }
    }
}
