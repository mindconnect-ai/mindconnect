package ai.mindconnect.llm.adapter.memory;

import ai.mindconnect.scope.TenantMap;
import ai.mindconnect.scope.TenantScope;
import ai.mindconnect.common.Versions;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.management.api.domain.LlmConfigId;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link LlmConfigRepository} — process-lifetime storage, no persistence. */
public class InMemoryLlmConfigRepository implements LlmConfigRepository {

    private final TenantScope scope;
    private final TenantMap<Map<LlmConfigId, LlmConfig>> store;

    public InMemoryLlmConfigRepository(TenantScope scope) {
        this.scope = scope;
        this.store = new TenantMap<>(ConcurrentHashMap::new);
    }

    @Override
    public void save(LlmConfig config) {
        store.of(scope).compute(config.id(), (id, current) -> config.withVersion(Versions.next(
                current == null ? null : current.version(), config.version(), "LlmConfig", id.value())));
    }

    @Override
    public Optional<LlmConfig> findById(LlmConfigId id) {
        return Optional.ofNullable(store.of(scope).get(id));
    }

    @Override
    public Optional<LlmConfig> findByName(String name) {
        return store.of(scope).values().stream()
                .filter(c -> c.name().equals(name))
                .findFirst();
    }

    @Override
    public List<LlmConfig> findAll() {
        return store.of(scope).values().stream().toList();
    }

    @Override
    public void deleteById(LlmConfigId id) {
        store.of(scope).remove(id);
    }
}
