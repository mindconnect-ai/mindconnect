package ai.mindconnect.agent.runtime.adapter.repo.memory;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.PerNamespace;
import ai.mindconnect.agent.ScopeSupplier;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link AgentDefinitionRepository} — process-lifetime storage, no persistence. */
public class InMemoryAgentDefinitionRepository implements AgentDefinitionRepository {

    private final PerNamespace<Map<AgentId, AgentDefinition>> store;

    public InMemoryAgentDefinitionRepository(ScopeSupplier scope) {
        this.store = new PerNamespace<>(scope, ConcurrentHashMap::new);
    }

    @Override
    public AgentDefinition save(AgentDefinition definition) {
        return store.current().compute(definition.id(), (id, current) -> definition.withVersion(ai.mindconnect.common.Versions.next(
                current == null ? null : current.version(), definition.version(), "AgentDefinition", id.value())));
    }

    @Override
    public Optional<AgentDefinition> findById(AgentId id) {
        return Optional.ofNullable(store.current().get(id));
    }

    @Override
    public List<AgentDefinition> findAll() {
        return store.current().values().stream().toList();
    }

    @Override
    public Optional<AgentDefinition> findByName(String name) {
        return store.current().values().stream()
                .filter(d -> d.name().equals(name))
                .findFirst();
    }

    @Override
    public void deleteById(AgentId id) {
        store.current().remove(id);
    }
}
