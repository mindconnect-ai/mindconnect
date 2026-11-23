package ai.mindconnect.agent.starter.runtime.bridge;

import java.util.Optional;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;
import ai.mindconnect.agent.runtime.domain.AgentDefinitionStatus;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;

/** The agents of the application's repository, by name: how a sub-agent an agent names is found. */
final class RepositoryAgentResolver implements AgentResolver {

    private final AgentDefinitionRepository agents;
    private final AgentDefinitionMapper mapper;

    RepositoryAgentResolver(AgentDefinitionRepository agents, AgentDefinitionMapper mapper) {
        this.agents = agents;
        this.mapper = mapper;
    }

    @Override
    public Optional<AgentDefinition> resolve(String agentName, Session session) {
        return agents.findByName(agentName)
                .filter(stored -> stored.status() == AgentDefinitionStatus.ACTIVE)
                .map(mapper::toRuntime);
    }
}
