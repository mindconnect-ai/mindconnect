package ai.mindconnect.llm.adapter.memory;

import ai.mindconnect.agent.PerNamespace;
import ai.mindconnect.agent.ScopeSupplier;
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

    private final PerNamespace<Map<LlmConfigId, LlmConfig>> store;

    public InMemoryLlmConfigRepository(ScopeSupplier scope) {
        this.store = new PerNamespace<>(scope, ConcurrentHashMap::new);
    }

    @Override
    public void save(LlmConfig config) {
        store.current().compute(config.id(), (id, current) -> config.withVersion(Versions.next(
                current == null ? null : current.version(), config.version(), "LlmConfig", id.value())));
    }

    @Override
    public Optional<LlmConfig> findById(LlmConfigId id) {
        return Optional.ofNullable(store.current().get(id));
    }

    @Override
    public Optional<LlmConfig> findByName(String name) {
        return store.current().values().stream()
                .filter(c -> c.name().equals(name))
                .findFirst();
    }

    @Override
    public List<LlmConfig> findAll() {
        return store.current().values().stream().toList();
    }

    @Override
    public void deleteById(LlmConfigId id) {
        store.current().remove(id);
    }
}
