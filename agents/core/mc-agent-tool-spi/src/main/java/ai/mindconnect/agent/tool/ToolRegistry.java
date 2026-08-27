package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.common.Namespace;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ToolRegistry {
    Optional<Tool> resolve(AgentTool agentTool, Namespace namespace, String userId, UUID sessionId);

    default List<Tool> resolveAll(List<AgentTool> agentTools, Namespace namespace, String userId, UUID sessionId) {
        return agentTools.stream()
                .filter(AgentTool::enabled)
                .map(t -> resolve(t, namespace, userId, sessionId))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    /**
     * Names of all tools known to this registry. Used by admin UIs to render
     * a selection dropdown when configuring an agent's tool list. Order is
     * deterministic (typically registration order). Default: empty for
     * registries that don't enumerate.
     */
    default Set<String> knownToolNames() { return Set.of(); }

    /**
     * Every resolvable tool name, grouped by its rubric ({@code ToolFactory.group()} /
     * {@code MultiToolProvider.group()}) — for catalogs and pickers that present
     * tools by category (agent functions, web tools, document tools, …). Groups
     * and names are sorted; like {@link #knownToolNames()} the view reflects the
     * registry's <em>current</em> state. Default: everything under "General".
     */
    default Map<String, Set<String>> toolNamesByGroup() {
        Set<String> names = knownToolNames();
        return names.isEmpty() ? Map.of() : Map.of("general", names);
    }

    /**
     * The config-overrides schema a tool declares ({@code ToolFactory.overridesSchema()}),
     * so admin UIs can show which override keys a tool supports. Empty for
     * tools that declare none (e.g. MCP tools, whose knobs are all parameters).
     */
    default Map<String, Object> overridesSchema(String toolName) { return Map.of(); }
}
