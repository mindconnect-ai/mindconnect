package ai.mindconnect.agent.runtime.api.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What an agent is: its instructions, the model it prefers, the tools it may
 * use. A value — where it is kept and how it is found is an
 * {@code AgentResolver}'s business.
 *
 * @param name          what the agent is called and found by. A name, not an
 *                      id: an agent in a project directory replaces the one
 *                      of the same name in the home directory, which only a
 *                      name can say
 * @param description   when to use the agent — what a calling agent's model
 *                      reads to decide whether to hand it a task
 * @param modelSettings the agent's layer of the model settings; a request or
 *                      a session may override any of it
 * @param tools         the tools the agent may use, resolved by name through
 *                      a {@code ToolResolver}; each says whether it is
 *                      offered at once or only after a search
 * @param maxRounds     the model rounds one turn may take
 * @param metadata      whatever else belongs to the agent and is not the
 *                      runtime's business: a capability's settings for it —
 *                      the sub-agents it may hand a task to, say — or what a
 *                      user interface shows. The runtime carries it and
 *                      never reads it; whoever puts something here names
 *                      the key after itself, and keeps to values that
 *                      survive being stored — strings, numbers, booleans,
 *                      lists and maps
 */
public record AgentDefinition(
        String name,
        String description,
        String instructions,
        ModelSettings modelSettings,
        List<ToolBinding> tools,
        int maxRounds,
        Map<String, Object> metadata
) {

    public AgentDefinition {
        tools = List.copyOf(tools);
        metadata = Map.copyOf(metadata);
    }

    /** This agent with other model settings: how a turn keeps the settings it runs with. */
    public AgentDefinition withModelSettings(ModelSettings settings) {
        return new AgentDefinition(name, description, instructions, settings, tools, maxRounds, metadata);
    }

    /** Whether the agent may use the tool now, offered at once or not. */
    public boolean uses(String toolName) {
        return binding(toolName).isPresent();
    }

    /** How this agent uses the tool; empty when it does not name it or has it switched off. */
    public Optional<ToolBinding> binding(String toolName) {
        return tools.stream().filter(tool -> tool.name().equals(toolName)).filter(ToolBinding::enabled).findFirst();
    }
}
