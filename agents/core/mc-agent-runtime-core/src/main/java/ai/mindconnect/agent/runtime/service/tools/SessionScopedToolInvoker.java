package ai.mindconnect.agent.runtime.service.tools;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.StreamEvent;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.runtime.service.InlineAgentTools;
import ai.mindconnect.agent.runtime.service.SessionAgentResolver;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.service.task.SessionTools;
import ai.mindconnect.agent.runtime.service.task.SubAgentSupport;
import ai.mindconnect.agent.runtime.service.turn.ToolExecutor;
import ai.mindconnect.agent.runtime.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.agent.tool.ScopedToolInvoker;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.common.LoggingContext;
import ai.mindconnect.llm.domain.ToolCall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The runtime's {@link ScopedToolInvoker}: resolves the caller's own toolset
 * through {@link SessionTools} and runs one call through the same
 * {@link ToolExecutor} — and therefore the same advisor chain — a
 * model-issued call goes through.
 *
 * <p>Deliberately NOT done here, and each for a reason:
 * <ul>
 *   <li><b>No conversation.</b> Nothing is appended. An inner call that
 *       became a {@code TOOL_CALL}/{@code TOOL_RESULT} pair would put back
 *       into the model's context exactly what the caller took the detour to
 *       keep out of it. The caller reports inner calls as metadata on its own
 *       result instead.</li>
 *   <li><b>No stream events.</b> The executor publishes to a sink that goes
 *       nowhere: live cards are paired with conversation messages in the UI,
 *       and these calls have none. Making them visible is a UI decision, not
 *       a side effect of running them.</li>
 *   <li><b>No approval gate.</b> It sits in front of the OUTER call, where a
 *       human can still answer without a sandboxed program waiting on them.</li>
 * </ul>
 *
 * <p>The registry is taken as a supplier: tool factories ask the environment
 * for this port while they bind, which is while the registry is being built.
 * Resolving it then would close the circle; resolving it per call cannot.
 */
public final class SessionScopedToolInvoker implements ScopedToolInvoker {

    private static final Logger log = LoggerFactory.getLogger(SessionScopedToolInvoker.class);

    /**
     * Never reachable from a program, whatever the agent has bound:
     * delegation is a task with a suspension of its own and does not fit
     * inside one blocking call. A caller cannot reach itself either, which
     * is handled per call — the name is not fixed here.
     */
    private static final Set<String> BLOCKED = Set.of(
            InlineAgentTools.RUN_AGENT, InlineAgentTools.RUN_AGENTS);

    /** A sink for the executor's events: see the class comment. */
    private static final Consumer<StreamEvent> NO_STREAM = event -> { };

    private final Supplier<ToolRegistry> toolRegistry;
    private final AgentSessionService sessions;
    private final AgentDefinitionRepository definitions;
    private final DynamicToolActivations activations;
    private final ToolExecutor executor;
    private final SubAgentSupport subAgents;

    public SessionScopedToolInvoker(Supplier<ToolRegistry> toolRegistry,
                                    AgentSessionService sessions,
                                    AgentDefinitionRepository definitions,
                                    DynamicToolActivations activations,
                                    ToolExecutor executor,
                                    SubAgentSupport subAgents) {
        this.toolRegistry = toolRegistry;
        this.sessions = sessions;
        this.definitions = definitions;
        this.activations = activations;
        this.executor = executor;
        this.subAgents = subAgents == null ? SubAgentSupport.disabled() : subAgents;
    }

    @Override
    public List<String> callableTools(ToolCallScope scope, String callerToolName) {
        AgentSession session = sessionOf(scope);
        if (session == null) {
            return List.of();
        }
        return callableTools(session, callerToolName);
    }

    private List<String> callableTools(AgentSession session, String callerToolName) {
        return toolsOf(session).liveToolNames().stream()
                .filter(name -> callable(name, callerToolName))
                .sorted()
                .toList();
    }

    @Override
    public Result invoke(ToolCallScope scope, String callerToolName, String toolName,
                         Map<String, Object> arguments) {
        if (toolName == null || toolName.isBlank()) {
            return Result.failure("Error: no tool name given.");
        }
        if (!callable(toolName, callerToolName)) {
            return Result.failure("Error: tool '" + toolName + "' cannot be called from "
                    + describe(callerToolName) + ".");
        }
        AgentSession session = sessionOf(scope);
        if (session == null) {
            return Result.failure("Error: tool '" + toolName
                    + "' needs a session; this call has none.");
        }
        List<Tool> resolved;
        try {
            resolved = toolsOf(session).liveTool(toolName);
        } catch (RuntimeException e) {
            log.warn("Resolving tool '{}' for {} failed: {}", toolName, describe(callerToolName), e.toString());
            return Result.failure("Error: tool '" + toolName + "' could not be resolved: " + e.getMessage());
        }
        if (resolved.isEmpty()) {
            // The model's own mistake gets the list it may pick from; so does a
            // program, which is written by the same model.
            return Result.failure("Error: unknown tool '" + toolName + "'. Available: "
                    + String.join(", ", callableTools(session, callerToolName)));
        }
        ToolCall call = new ToolCall("inner_" + UUID.randomUUID(), toolName, arguments == null ? Map.of() : arguments);
        try (var ignored = LoggingContext.tool(callerToolName + " → " + toolName)) {
            ToolExecutor.Result result = executor.execute(call, resolved, context(session, scope.agentId()));
            String output = result.resultText() == null ? "" : result.resultText();
            // A tool that RETURNS an error text failed as far as its caller is
            // concerned, even though nothing threw — the same reading the tool
            // task applies to a model-issued call.
            boolean failed = result.failed() || output.startsWith("Error:");
            return new Result(output, failed, result.durationMs());
        }
    }

    /** The executor's per-call context; the scope it derives is what the advisors see. */
    private ToolExecutor.Context context(AgentSession session, AgentId agentId) {
        return new ToolExecutor.Context(NO_STREAM, session.conversationId(), agentId,
                null, session.userId(), session.id());
    }

    /**
     * The session a call belongs to, or {@code null} when there is none: a
     * tool bound for the catalog or a test bench has no chat whose tools it
     * could run.
     */
    private AgentSession sessionOf(ToolCallScope scope) {
        SessionId sessionId = scope == null ? null : scope.sessionId();
        if (sessionId == null) {
            return null;
        }
        try {
            return sessions.findSession(sessionId);
        } catch (RuntimeException e) {
            log.warn("No session {} for a scoped tool call: {}", sessionId, e.toString());
            return null;
        }
    }

    /** The session's toolset — the same one a model-issued call resolves against. */
    private SessionTools toolsOf(AgentSession session) {
        AgentDefinition def = new SessionAgentResolver(definitions).resolve(session);
        // A sub-agent's tools see the chat that started the chain — its uploads
        // are there — exactly as they do for a model-issued call.
        SessionId root = session.parentSessionId() == null
                ? session.id() : sessions.rootSession(session.id()).id();
        return new SessionTools(toolRegistry.get(), activations, def, session, root, subAgents);
    }

    private static boolean callable(String toolName, String callerToolName) {
        return !BLOCKED.contains(toolName) && !toolName.equals(callerToolName);
    }

    private static String describe(String callerToolName) {
        return callerToolName == null || callerToolName.isBlank() ? "a tool" : "'" + callerToolName + "'";
    }
}
