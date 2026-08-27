package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;

import java.util.Map;

/**
 * Lets an agent expose a registry tool under its own name. The override
 * {@code {"tool": "vector_search"}} means: resolve THAT factory, but present
 * it to the LLM as this {@link AgentTool}'s {@code name} (and, when set, its
 * {@code description}). An agent can therefore carry the same underlying tool
 * twice without collision — e.g. {@code search_project_docs} pinned to a
 * knowledge store next to the plain session-scoped {@code vector_search} —
 * and the alias name itself documents intent to the model.
 *
 * <p>Applied by {@link SpiToolRegistry#resolve} before parameter pinning, so
 * pins reference the underlying tool's real parameter names.
 */
public final class AliasTool implements Tool {

    /** Override key naming the registry tool to resolve instead of the agent-tool name. */
    public static final String OVERRIDE_KEY = "tool";

    private final Tool delegate;
    private final String name;
    private final String description;

    private AliasTool(Tool delegate, String name, String description) {
        this.delegate = delegate;
        this.name = name;
        this.description = description;
    }

    /** The registry name to resolve for this agent tool: alias target, else the tool's own name. */
    public static String registryName(AgentTool agentTool) {
        return agentTool.overrides().get(OVERRIDE_KEY) instanceof String target && !target.isBlank()
                ? target
                : agentTool.name();
    }

    /** Wraps {@code delegate} under the agent tool's name when aliased; identity otherwise. */
    public static Tool wrap(AgentTool agentTool, Tool delegate) {
        if (agentTool == null || delegate == null
                || registryName(agentTool).equals(agentTool.name())) {
            return delegate;
        }
        return new AliasTool(delegate, agentTool.name(),
                agentTool.description() == null || agentTool.description().isBlank()
                        ? delegate.description()
                        : agentTool.description());
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return delegate.parametersSchema();
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return delegate.execute(arguments);
    }

    @Override
    public boolean streamsResultToUser() {
        return delegate.streamsResultToUser();
    }
}
