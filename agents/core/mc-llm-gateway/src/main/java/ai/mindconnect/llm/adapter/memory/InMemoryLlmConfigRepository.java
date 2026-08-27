package ai.mindconnect.llm.adapter.memory;

import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.out.LlmConfigRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link LlmConfigRepository} — process-lifetime storage, no persistence. */
public class InMemoryLlmConfigRepository implements LlmConfigRepository {

    private final Map<UUID, LlmConfig> store = new ConcurrentHashMap<>();

    @Override
    public void save(LlmConfig config) {
        store.put(config.id(), config);
    }

    @Override
    public Optional<LlmConfig> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<LlmConfig> findByName(String name) {
        return store.values().stream().filter(c -> c.name().equals(name)).findFirst();
    }

    @Override
    public List<LlmConfig> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void deleteById(UUID id) {
        store.remove(id);
    }
}
