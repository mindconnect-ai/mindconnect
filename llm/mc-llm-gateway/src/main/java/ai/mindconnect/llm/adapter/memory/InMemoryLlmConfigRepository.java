package ai.mindconnect.llm.adapter.memory;

import ai.mindconnect.llm.management.api.port.out.LlmScopeSupplier;
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

    private final LlmScopeSupplier scope;
    private final Map<String, Map<LlmConfigId, LlmConfig>> store;

    public InMemoryLlmConfigRepository(LlmScopeSupplier scope) {
        this.scope = scope;
        this.store = new ConcurrentHashMap<>();
    }

    @Override
    public void save(LlmConfig config) {
        namespace().compute(config.id(), (id, current) -> config.withVersion(Versions.next(
                current == null ? null : current.version(), config.version(), "LlmConfig", id.value())));
    }

    @Override
    public Optional<LlmConfig> findById(LlmConfigId id) {
        return Optional.ofNullable(namespace().get(id));
    }

    @Override
    public Optional<LlmConfig> findByName(String name) {
        return namespace().values().stream()
                .filter(c -> c.name().equals(name))
                .findFirst();
    }

    @Override
    public List<LlmConfig> findAll() {
        return namespace().values().stream().toList();
    }

    @Override
    public void deleteById(LlmConfigId id) {
        namespace().remove(id);
    }

    /** The entries of the namespace the scope names now, empty the first time it asks. */
    private Map<LlmConfigId, LlmConfig> namespace() {
        return store.computeIfAbsent(scope.namespace(), namespace -> new ConcurrentHashMap<>());
    }
}
