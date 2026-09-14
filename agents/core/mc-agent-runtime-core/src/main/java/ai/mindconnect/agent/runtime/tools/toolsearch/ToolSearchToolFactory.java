package ai.mindconnect.agent.runtime.tools.toolsearch;

import ai.mindconnect.agent.tool.ToolRegistryRef;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Registers {@code tool_search} (see {@link ToolSearchTool}). Available only
 * when the host wiring provides the {@link ToolRegistryRef} and
 * {@link DynamicToolActivations} services — a host that doesn't opt in simply
 * has no search tool.
 *
 * <p>Never bound by hand: the runtime adds it to an agent that has deferred
 * tools and hands it their names as the {@code assigned} override.
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
    public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new ToolSearchTool(registryRef, activations,
                scope == null ? null : scope.sessionId(),
                names(agentTool, "assigned"));
    }

    /** Reads a string-collection override. */
    private static Set<String> names(AgentTool agentTool, String key) {
        if (agentTool == null || !(agentTool.overrides().get(key) instanceof Collection<?> raw)) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (Object entry : raw) {
            String value = String.valueOf(entry).trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }
}
