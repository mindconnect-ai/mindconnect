package ai.mindconnect.agent.runtime.port.out;


import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.AgentId;

import java.util.List;
import java.util.Optional;

/**
 * Storage of agent definitions. An adapter is bound to one namespace when it
 * is built; every method works inside it.
 */
public interface AgentDefinitionRepository {

    AgentDefinition save(AgentDefinition definition);

    Optional<AgentDefinition> findById(AgentId id);

    /** Every agent. */
    List<AgentDefinition> findAll();

    /** The agent of that name — names are unique. */
    Optional<AgentDefinition> findByName(String name);

    /** No-op when there is no such agent. */
    void deleteById(AgentId id);

    default boolean exists(AgentId id) {
        return findById(id).isPresent();
    }
}
