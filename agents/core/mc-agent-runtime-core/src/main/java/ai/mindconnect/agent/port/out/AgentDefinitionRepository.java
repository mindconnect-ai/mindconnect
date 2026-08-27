package ai.mindconnect.agent.port.out;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.common.Namespace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentDefinitionRepository {

    AgentDefinition save(AgentDefinition definition);

    Optional<AgentDefinition> findById(UUID id);

    List<AgentDefinition> findByNamespace(Namespace namespace);

    Optional<AgentDefinition> findByName(Namespace namespace, String name);

    void deleteById(UUID id);
}
