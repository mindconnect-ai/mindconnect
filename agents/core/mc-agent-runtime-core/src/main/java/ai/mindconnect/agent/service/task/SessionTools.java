package ai.mindconnect.agent.service.task;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.service.InlineAgentTools;
import ai.mindconnect.agent.service.round.ToolDefinitionProvider;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.llm.domain.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
        return toolRegistry.resolveAll(refs, session.namespace(), session.userId(), session.id());
    }

    /** The executable tools of this moment — configured plus search-activated. */
    public List<Tool> liveTools() {
        // The inline delegation tools have no registry implementation on
        // purpose — resolving them would only produce a spurious error log.
        List<AgentTool> refs = dynamicToolActivations.effectiveRefs(def, session.id()).stream()
                .filter(ref -> !InlineAgentTools.RUN_AGENT.equals(ref.name())
                        && !InlineAgentTools.RUN_AGENTS.equals(ref.name()))
                .toList();
        return toolRegistry.resolveAll(refs, session.namespace(), session.userId(), session.id());
    }

    @Override
    public List<ToolDefinition> toolDefinitions(UUID sessionId) {
        List<ToolDefinition> defs = new ArrayList<>(liveTools().stream()
                .map(t -> ToolDefinition.of(t.name(), t.description(), t.parametersSchema()))
                .toList());
        if (enabled(InlineAgentTools.RUN_AGENT)) defs.add(InlineAgentTools.runAgentDefinition());
        if (enabled(InlineAgentTools.RUN_AGENTS)) defs.add(InlineAgentTools.runAgentsDefinition());
        return defs;
    }

    /** Whether {@code toolName} is one of the inline delegation tools this agent enables. */
    public boolean isInline(String toolName) {
        return (InlineAgentTools.RUN_AGENT.equals(toolName)
                || InlineAgentTools.RUN_AGENTS.equals(toolName)) && enabled(toolName);
    }

    private boolean enabled(String toolName) {
        return def.tools().stream().anyMatch(t -> toolName.equals(t.name()) && t.enabled());
    }
}
