package ai.mindconnect.agent.runtime.capabilities.subagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;
import ai.mindconnect.agent.runtime.api.port.out.Tool;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * Sub-agents: an agent that names sub-agents gets {@code run_agent} to hand
 * one of them a task, {@code run_agents} to hand out several at once and
 * {@code list_agents} to see who there is, and a child session may write to
 * the one it came from. All are ordinary tools; the engine only knows that
 * a tool may hand its call to other turns.
 *
 * <p>One tool with the agent as a parameter and not a tool per agent: the
 * model learns once what delegating is, an agent may be named like a tool,
 * and the list of tools stays the same when an agent comes or goes.
 */
public final class SubAgentPlugin implements Plugin {

    /**
     * The key in an agent's metadata under which it names the agents it may
     * hand a task to: a list of agent names. Here and not a field of the
     * agent, because only this plugin reads it.
     */
    public static final String SUB_AGENTS = "subAgents";

    /** The agents an agent may hand a task to; none when it names none. */
    public static List<String> subAgentsOf(AgentDefinition agent) {
        return agent.metadata().get(SUB_AGENTS) instanceof List<?> names
                ? names.stream().map(String::valueOf).toList()
                : List.of();
    }

    @Override
    public String name() {
        return "sub-agents";
    }

    @Override
    public void install(PluginRegistry registry) {
        AgentResolver agents = AgentResolver.firstOf(() -> registry.all(AgentResolver.class));
        SendToParentTool sendToParent = new SendToParentTool(registry.supplier(TurnService.class));

        registry.contribute(ToolResolver.class, new ToolResolver() {
            // Offered at the hook below, not here: only an agent that names sub-agents has these.
            @Override
            public List<ToolDefinition> tools(AgentDefinition agent, Session session) {
                return List.of();
            }

            @Override
            public Optional<Tool> resolve(String toolName, AgentDefinition caller, Session session) {
                if (SendToParentTool.NAME.equals(toolName)) {
                    return Optional.of(sendToParent);
                }
                if (subAgentsOf(caller).isEmpty()) {
                    return Optional.empty();
                }
                RunAgentTool single = new RunAgentTool(caller, agents, registry.supplier(SessionService.class));
                return switch (toolName) {
                    case RunAgentTool.NAME -> Optional.of(single);
                    case RunAgentsTool.NAME -> Optional.of(new RunAgentsTool(single));
                    case ListAgentsTool.NAME -> Optional.of(new ListAgentsTool(caller, agents));
                    default -> Optional.empty();
                };
            }
        });

        registry.after(RuntimeHook.TOOL_OFFER, (context, tools) -> {
            List<ToolDefinition> offered = new ArrayList<>(tools);
            if (!subAgentsOf(context.agent()).isEmpty()) {
                offered.add(RunAgentTool.definition(context.agent()));
                offered.add(RunAgentsTool.DEFINITION);
                offered.add(ListAgentsTool.DEFINITION);
            }
            if (context.session().parentId() != null) {
                offered.add(SendToParentTool.DEFINITION);
            }
            return offered;
        });
    }
}
