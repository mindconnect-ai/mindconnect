package ai.mindconnect.agent.runtime.service.task;

import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.service.InlineAgentTools;
import ai.mindconnect.agent.runtime.service.round.ToolDefinitionProvider;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.runtime.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.llm.domain.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * One turn's toolset — resolved PER CALL, not captured per turn: a
 * {@code tool_search} in round N activates tools that round N+1 must already
 * offer and execute. The inline delegation tools ({@code run_agent},
 * {@code run_agents}) have no registry entry; their definitions are appended
 * when the agent enables them, and {@link #isInline} tells the executor to
 * route them to the sub-agent runner instead of the registry.
 */
public final class SessionTools implements ToolDefinitionProvider {

    private final ToolRegistry toolRegistry;
    private final DynamicToolActivations dynamicToolActivations;
    private final AgentDefinition def;
    private final AgentSession session;

    public SessionTools(ToolRegistry toolRegistry, DynamicToolActivations dynamicToolActivations,
                        AgentDefinition def, AgentSession session) {
        this.toolRegistry = toolRegistry;
        this.dynamicToolActivations = dynamicToolActivations;
        this.def = def;
        this.session = session;
    }

    /**
     * Resolves ONLY the tool a call names — the tool worker's path:
     * executing one call must not resolve (and possibly connect) the whole
     * toolset. Empty when the agent has no such tool; the executor then
     * reports the unknown tool exactly as before.
     */
    public List<Tool> liveTool(String toolName) {
        List<AgentTool> refs = dynamicToolActivations.effectiveRefs(def, session.id()).stream()
                .filter(ref -> toolName.equals(ref.name()))
                .toList();
        return toolRegistry.resolveAll(refs, scope());
    }

    /** The executable tools of this moment — configured plus search-activated. */
    public List<Tool> liveTools() {
        // The inline delegation tools have no registry implementation on
        // purpose — resolving them would only produce a spurious error log.
        List<AgentTool> refs = dynamicToolActivations.effectiveRefs(def, session.id()).stream()
                .filter(ref -> !InlineAgentTools.RUN_AGENT.equals(ref.name())
                        && !InlineAgentTools.RUN_AGENTS.equals(ref.name()))
                .toList();
        return toolRegistry.resolveAll(refs, scope());
    }

    @Override
    public List<ToolDefinition> toolDefinitions(SessionId sessionId) {
        List<ToolDefinition> defs = new ArrayList<>(liveTools().stream()
                .map(t -> ToolDefinition.of(t.name(), t.description(), t.parametersSchema()))
                .toList());
        if (enabled(InlineAgentTools.RUN_AGENT)) defs.add(InlineAgentTools.runAgentDefinition());
        if (enabled(InlineAgentTools.RUN_AGENTS)) defs.add(InlineAgentTools.runAgentsDefinition());
        return defs;
    }

    /** Whether {@code toolName} is one of the inline delegation tools this agent enables. */
    /** Who is calling, for the factories and advisors: this session, its user, this agent. */
    private ToolCallScope scope() {
        return new ToolCallScope(session.userId(), session.id(), def.id());
    }

    public boolean isInline(String toolName) {
        return (InlineAgentTools.RUN_AGENT.equals(toolName)
                || InlineAgentTools.RUN_AGENTS.equals(toolName)) && enabled(toolName);
    }

    private boolean enabled(String toolName) {
        return def.tools().stream().anyMatch(t -> toolName.equals(t.name()) && t.enabled());
    }
}
