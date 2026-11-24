package ai.mindconnect.llm.adapter.memory;

import ai.mindconnect.agent.scope.NamespaceMap;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.common.Versions;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.port.out.LlmConfigRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link LlmConfigRepository} — process-lifetime storage, no persistence. */
public class InMemoryLlmConfigRepository implements LlmConfigRepository {

    private final ScopeSupplier scope;
    private final NamespaceMap<Map<LlmConfigId, LlmConfig>> store;

    public InMemoryLlmConfigRepository(ScopeSupplier scope) {
        this.scope = scope;
        this.store = new NamespaceMap<>(ConcurrentHashMap::new);
    }

    @Override
    public void save(LlmConfig config) {
        store.of(scope.namespace()).compute(config.id(), (id, current) -> config.withVersion(Versions.next(
                current == null ? null : current.version(), config.version(), "LlmConfig", id.value())));
    }

    @Override
    public Optional<LlmConfig> findById(LlmConfigId id) {
        return Optional.ofNullable(store.of(scope.namespace()).get(id));
    }

    @Override
    public Optional<LlmConfig> findByName(String name) {
        return store.of(scope.namespace()).values().stream()
                .filter(c -> c.name().equals(name))
                .findFirst();
    }

    @Override
    public List<LlmConfig> findAll() {
        return store.of(scope.namespace()).values().stream().toList();
    }

    @Override
    public void deleteById(LlmConfigId id) {
        store.of(scope.namespace()).remove(id);
    }
}
