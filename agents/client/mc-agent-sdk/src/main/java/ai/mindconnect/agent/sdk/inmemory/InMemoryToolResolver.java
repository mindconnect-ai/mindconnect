package ai.mindconnect.agent.sdk.inmemory;

import java.util.List;
import java.util.Optional;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.port.out.Tool;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;

/** Tools built in code and handed over as a list, the same for every session. */
public final class InMemoryToolResolver implements ToolResolver {

    private final ToolResolver tools;

    public InMemoryToolResolver(List<Tool> tools) {
        this.tools = ToolResolver.of(tools);
    }

    @Override
    public List<ToolDefinition> tools(AgentDefinition agent, Session session) {
        return tools.tools(agent, session);
    }

    @Override
    public Optional<Tool> resolve(String toolName, AgentDefinition agent, Session session) {
        return tools.resolve(toolName, agent, session);
    }
}
