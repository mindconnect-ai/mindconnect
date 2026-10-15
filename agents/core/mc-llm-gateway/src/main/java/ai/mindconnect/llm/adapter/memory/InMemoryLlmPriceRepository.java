package ai.mindconnect.llm.adapter.memory;

import ai.mindconnect.agent.PerNamespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.llm.domain.LlmPrice;
import ai.mindconnect.llm.domain.LlmPriceId;
import ai.mindconnect.llm.domain.LlmPriceList;
import ai.mindconnect.llm.port.out.LlmPriceRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link LlmPriceRepository} — process-lifetime storage, no persistence. */
public class InMemoryLlmPriceRepository implements LlmPriceRepository {

    private final PerNamespace<Map<LlmPriceId, LlmPrice>> store;

    public InMemoryLlmPriceRepository(ScopeSupplier scope) {
        this.store = new PerNamespace<>(scope, ConcurrentHashMap::new);
    }

    @Override
    public void save(LlmPrice price) {
        store.current().put(price.id(), price);
    }

    @Override
    public Optional<LlmPrice> findById(LlmPriceId id) {
        return Optional.ofNullable(store.current().get(id));
    }

    @Override
    public List<LlmPrice> findByConfigName(String configName) {
        return store.current().values().stream().filter(p -> p.configName().equals(configName))
                .sorted(LlmPriceList.CHRONOLOGICAL).toList();
    }

    @Override
    public List<LlmPrice> findAll() {
        return store.current().values().stream().sorted(LlmPriceList.CHRONOLOGICAL).toList();
    }

    @Override
    public void deleteById(LlmPriceId id) {
        store.current().remove(id);
    }
}
