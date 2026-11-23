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
 * Sub-agents: every agent an agent names as a sub-agent is offered to its
 * model as a tool under the agent's name, and a child session may write to
 * the one it came from. Both are ordinary tools; the engine only knows that
 * a tool may hand its call to another turn.
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
            // Offered per agent at the hook below, not here: an agent is a
            // tool only for the agents that name it.
            @Override
            public List<ToolDefinition> tools(Session session) {
                return List.of();
            }

            @Override
            public Optional<Tool> resolve(String toolName, Session session) {
                if (SendToParentTool.NAME.equals(toolName)) {
                    return Optional.of(sendToParent);
                }
                return agents.resolve(toolName, session)
                        .map(agent -> new SubAgentTool(agent, registry.supplier(SessionService.class)));
            }
        });

        registry.after(RuntimeHook.TOOL_OFFER, (context, tools) -> {
            List<ToolDefinition> offered = new ArrayList<>(tools);
            subAgentsOf(context.agent()).stream()
                    .map(name -> agents.resolve(name, context.session()))
                    .flatMap(Optional::stream)
                    .map(SubAgentTool::definition)
                    .forEach(offered::add);
            if (context.session().parentId() != null) {
                offered.add(SendToParentTool.DEFINITION);
            }
            return offered;
        });
    }
}
