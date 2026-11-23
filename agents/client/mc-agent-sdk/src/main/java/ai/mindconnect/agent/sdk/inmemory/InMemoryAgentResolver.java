package ai.mindconnect.agent.sdk.inmemory;

import java.util.List;
import java.util.Optional;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;

/** Agents built in code and handed over as a list: what a program embedding the runtime starts with. */
public final class InMemoryAgentResolver implements AgentResolver {

    private final List<AgentDefinition> agents;

    public InMemoryAgentResolver(List<AgentDefinition> agents) {
        this.agents = List.copyOf(agents);
    }

    @Override
    public Optional<AgentDefinition> resolve(String agentName, Session session) {
        return agents.stream().filter(agent -> agent.name().equals(agentName)).findFirst();
    }
}
