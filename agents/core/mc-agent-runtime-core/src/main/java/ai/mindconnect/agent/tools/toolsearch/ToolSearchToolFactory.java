package ai.mindconnect.agent.tools.toolsearch;

import ai.mindconnect.agent.tool.ToolRegistryRef;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Registers {@code tool_search} (see {@link ToolSearchTool}). Available only
 * when the host wiring provides the {@link ToolRegistryRef} and
 * {@link DynamicToolActivations} services — a host that doesn't opt in simply
 * has no search tool.
 *
 * <p>Config override {@code groups} (array of group names) narrows what an
 * agent may discover; without it the whole registry is searchable.
 */
public final class ToolSearchToolFactory implements ToolFactory {

    private ToolRegistryRef registryRef;
    private DynamicToolActivations activations;

    @Override
    public String name() {
        return "tool_search";
    }

    @Override
    public String group() {
        return "agents";
    }

    @Override
    public void bind(ToolEnvironment env) {
        this.registryRef = env.get(ToolRegistryRef.class).orElse(null);
        this.activations = env.get(DynamicToolActivations.class).orElse(null);
    }

    @Override
    public boolean isAvailable() {
        return registryRef != null && activations != null;
    }

    @Override
    public Map<String, Object> overridesSchema() {
        Map<String, Object> groups = new LinkedHashMap<>();
        groups.put("type", "array");
        groups.put("items", Map.of("type", "string"));
        groups.put("description", "Registry groups this agent may discover beyond its own deferred tools "
                + "(e.g. [\"web\", \"documents\"]; \"*\" = every group). Unset = only the agent's "
                + "deferred tools are searchable. Usually set via the agent's Tool Search settings, "
                + "not per tool row.");
        return Map.of("type", "object", "properties", Map.of("groups", groups));
    }

    @Override
    public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new ToolSearchTool(registryRef, activations,
                scope == null ? null : scope.namespace(),
                scope == null ? null : scope.sessionId(),
                names(agentTool, "assigned", false),
                names(agentTool, "groups", true));
    }

    /** Reads a string-collection override; groups are lowercased ("*" allowed). */
    private static Set<String> names(AgentTool agentTool, String key, boolean lowercase) {
        if (agentTool == null || !(agentTool.overrides().get(key) instanceof Collection<?> raw)) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (Object entry : raw) {
            String value = String.valueOf(entry).trim();
            if (lowercase) {
                value = value.toLowerCase(Locale.ROOT);
            }
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }
}
