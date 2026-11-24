package ai.mindconnect.agent.runtime.capabilities.subagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

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

    /**
     * The key in an agent's metadata saying whether other agents may call
     * it. {@code false} is for agents the host calls on its own, like a title
     * generator: no roster offers them and a call to them is refused. Absent
     * means yes.
     */
    public static final String CALLABLE = "callableByAgents";

    /** Whether other agents may call this one. */
    public static boolean callable(AgentDefinition agent) {
        return !Boolean.FALSE.equals(agent.metadata().get(CALLABLE));
    }

    /** The agents an agent may hand a task to; none when it names none. */
    public static List<String> subAgentsOf(AgentDefinition agent) {
        return agent.metadata().get(SUB_AGENTS) instanceof List<?> names
                ? names.stream().map(String::valueOf).toList()
                : List.of();
    }

    /**
     * The key in a sub-agent's session metadata that names the tool call
     * that opened it: whoever shows a conversation finds under a call the
     * sessions of the agents it ran.
     */
    public static final String OPENED_BY_CALL = "parentToolCallId";

    /** How deep sub-agents may call sub-agents where nothing else is said. */
    public static final int DEFAULT_MAX_DEPTH = 5;

    private final Predicate<String> inherited;
    private final int maxDepth;

    /**
     * The tools an agent gets by naming sub-agents, for a catalog to list:
     * nobody binds them by hand, so no registry of bindable tools knows them.
     */
    public static List<ToolDefinition> delegationTools() {
        return List.of(RunAgentTool.CATALOGUED, RunAgentsTool.DEFINITION, ListAgentsTool.DEFINITION);
    }

    /**
     * @param inherited which entries of a caller's session metadata its
     *                  sub-agent's session takes over, by key: where it
     *                  works, yes; what a store or a user interface keeps
     *                  of that one session, no
     * @param maxDepth  how many sessions deep a sub-agent may be: 1 lets an
     *                  agent call sub-agents that call nobody. A chain that
     *                  never ends costs without limit, so there is one.
     */
    public SubAgentPlugin(Predicate<String> inherited, int maxDepth) {
        this.inherited = inherited;
        this.maxDepth = maxDepth;
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
                RunAgentTool single = new RunAgentTool(caller, agents, registry.supplier(SessionService.class),
                        inherited, maxDepth);
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
