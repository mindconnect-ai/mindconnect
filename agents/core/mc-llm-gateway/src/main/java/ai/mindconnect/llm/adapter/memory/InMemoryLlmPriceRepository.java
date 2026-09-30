package ai.mindconnect.llm.adapter.memory;

import ai.mindconnect.llm.domain.LlmPrice;
import ai.mindconnect.llm.domain.LlmPriceId;
import ai.mindconnect.llm.domain.LlmPrices;
import ai.mindconnect.llm.port.out.LlmPriceRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link LlmPriceRepository} — process-lifetime storage, no persistence. */
public class InMemoryLlmPriceRepository implements LlmPriceRepository {

    private final Map<LlmPriceId, LlmPrice> store = new ConcurrentHashMap<>();

    @Override
    public void save(LlmPrice price) {
        store.put(price.id(), price);
    }

    @Override
    public Optional<LlmPrice> findById(LlmPriceId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<LlmPrice> findByConfigName(String configName) {
        return store.values().stream().filter(p -> p.configName().equals(configName))
                .sorted(LlmPrices.CHRONOLOGICAL).toList();
    }

    @Override
    public List<LlmPrice> findAll() {
        return store.values().stream().sorted(LlmPrices.CHRONOLOGICAL).toList();
    }

    @Override
    public void deleteById(LlmPriceId id) {
        store.remove(id);
    }
}
