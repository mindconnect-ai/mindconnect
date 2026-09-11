package ai.mindconnect.agent.runtime.adapter.repo.memory;

import ai.mindconnect.agent.AgentId;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link AgentDefinitionRepository} — process-lifetime storage, no persistence. */
public class InMemoryAgentDefinitionRepository implements AgentDefinitionRepository {

    private final Map<AgentId, AgentDefinition> store = new ConcurrentHashMap<>();

    @Override
    public AgentDefinition save(AgentDefinition definition) {
        store.put(definition.id(), definition);
        return definition;
    }

    @Override
    public Optional<AgentDefinition> findById(AgentId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<AgentDefinition> findAll() {
        return store.values().stream().toList();
    }

    @Override
    public Optional<AgentDefinition> findByName(String name) {
        return store.values().stream()
                .filter(d -> d.name().equals(name))
                .findFirst();
    }

    @Override
    public void deleteById(AgentId id) {
        store.remove(id);
    }
}
