package ai.mindconnect.llm.adapter.memory;

import ai.mindconnect.scope.TenantMap;
import ai.mindconnect.scope.TenantScope;
import ai.mindconnect.llm.management.api.domain.LlmPrice;
import ai.mindconnect.llm.management.api.domain.LlmPriceId;
import ai.mindconnect.llm.management.api.domain.LlmPriceList;
import ai.mindconnect.llm.management.api.port.out.LlmPriceRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link LlmPriceRepository} — process-lifetime storage, no persistence. */
public class InMemoryLlmPriceRepository implements LlmPriceRepository {

    private final TenantScope scope;
    private final TenantMap<Map<LlmPriceId, LlmPrice>> store;

    public InMemoryLlmPriceRepository(TenantScope scope) {
        this.scope = scope;
        this.store = new TenantMap<>(ConcurrentHashMap::new);
    }

    @Override
    public void save(LlmPrice price) {
        store.of(scope).put(price.id(), price);
    }

    @Override
    public Optional<LlmPrice> findById(LlmPriceId id) {
        return Optional.ofNullable(store.of(scope).get(id));
    }

    @Override
    public List<LlmPrice> findByConfigName(String configName) {
        return store.of(scope).values().stream().filter(p -> p.configName().equals(configName))
                .sorted(LlmPriceList.CHRONOLOGICAL).toList();
    }

    @Override
    public List<LlmPrice> findAll() {
        return store.of(scope).values().stream().sorted(LlmPriceList.CHRONOLOGICAL).toList();
    }

    @Override
    public void deleteById(LlmPriceId id) {
        store.of(scope).remove(id);
    }
}
