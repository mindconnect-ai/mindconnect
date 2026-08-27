package ai.mindconnect.agent.adapter.repo.memory;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.common.Namespace;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link AgentDefinitionRepository} — process-lifetime storage, no persistence. */
public class InMemoryAgentDefinitionRepository implements AgentDefinitionRepository {

    private final Map<UUID, AgentDefinition> store = new ConcurrentHashMap<>();

    @Override
    public AgentDefinition save(AgentDefinition definition) {
        store.put(definition.id(), definition);
        return definition;
    }

    @Override
    public Optional<AgentDefinition> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<AgentDefinition> findByNamespace(Namespace namespace) {
        return store.values().stream().filter(d -> d.namespace().equals(namespace)).toList();
    }

    @Override
    public Optional<AgentDefinition> findByName(Namespace namespace, String name) {
        return store.values().stream()
                .filter(d -> d.namespace().equals(namespace) && d.name().equals(name))
                .findFirst();
    }

    @Override
    public void deleteById(UUID id) {
        store.remove(id);
    }
}
