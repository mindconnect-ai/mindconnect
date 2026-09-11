package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;

import java.util.Map;

/**
 * Lets an agent expose a registry tool under its own name. The override
 * {@code {"tool": "vector_search"}} means: resolve THAT factory, but present
 * it to the LLM as this {@link AgentTool}'s {@code name}. An agent can
 * therefore carry the same underlying tool twice without collision — e.g.
 * {@code search_project_docs} pinned to a knowledge store next to the plain
 * session-scoped {@code vector_search} — and the alias name itself documents
 * intent to the model.
 *
 * <p>The <em>name</em> is all this changes. The agent's description is
 * applied by {@link DescribedTool}, for aliased and plain tools alike:
 * carrying it here meant it only ever reached the model when a tool happened
 * to be aliased, which made the description field on every other agent tool
 * look effective while doing nothing.
 *
 * <p>Applied by {@link SpiToolRegistry} before parameter pinning, so pins
 * reference the underlying tool's real parameter names.
 */
public final class AliasTool implements Tool {

    /** Override key naming the registry tool to resolve instead of the agent-tool name. */
    public static final String OVERRIDE_KEY = "tool";

    private final Tool delegate;
    private final String name;

    private AliasTool(Tool delegate, String name) {
        this.delegate = delegate;
        this.name = name;
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
        return new AliasTool(delegate, agentTool.name());
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return delegate.description();
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
