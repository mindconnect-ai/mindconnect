package ai.mindconnect.agent.runtime.api.port.out;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;

/**
 * Where tools come from: the classpath, a list handed over in code, the MCP
 * servers of a configuration.
 *
 * <p>It answers one question only — which tools exist. Whether a session
 * sees a tool is decided elsewhere and later: the agent has to name it, and
 * the hooks at {@code RuntimeHook.TOOL_OFFER} may take it away. Several
 * resolvers stand side by side as contributions; the first that knows a
 * name wins.
 */
public interface ToolResolver {

    /**
     * Every tool this resolver has for the agent in the session, whether or
     * not anyone may use it. The agent is asked for because it may set a
     * tool up its own way: see {@code ToolBinding.metadata}.
     */
    List<ToolDefinition> tools(AgentDefinition agent, Session session);

    Optional<Tool> resolve(String toolName, AgentDefinition agent, Session session);

    /** Several resolvers as one: every tool of each, and by name the first that knows it. */
    static ToolResolver firstOf(Supplier<List<ToolResolver>> resolvers) {
        return new ToolResolver() {
            @Override
            public List<ToolDefinition> tools(AgentDefinition agent, Session session) {
                return resolvers.get().stream().flatMap(resolver -> resolver.tools(agent, session).stream()).toList();
            }

            @Override
            public Optional<Tool> resolve(String toolName, AgentDefinition agent, Session session) {
                return resolvers.get().stream()
                        .map(resolver -> resolver.resolve(toolName, agent, session))
                        .flatMap(Optional::stream)
                        .findFirst();
            }
        };
    }

    /** A fixed set of tools, the same for every agent and session. */
    static ToolResolver of(List<Tool> tools) {
        List<Tool> fixed = List.copyOf(tools);
        return new ToolResolver() {
            @Override
            public List<ToolDefinition> tools(AgentDefinition agent, Session session) {
                return fixed.stream().map(Tool::definition).toList();
            }

            @Override
            public Optional<Tool> resolve(String toolName, AgentDefinition agent, Session session) {
                return fixed.stream().filter(tool -> tool.definition().name().equals(toolName)).findFirst();
            }
        };
    }
}
