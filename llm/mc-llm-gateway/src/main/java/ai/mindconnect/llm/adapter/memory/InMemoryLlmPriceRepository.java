package ai.mindconnect.llm.adapter.memory;

import ai.mindconnect.llm.management.api.port.out.LlmScopeSupplier;
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

    private final LlmScopeSupplier scope;
    private final Map<String, Map<LlmPriceId, LlmPrice>> store;

    public InMemoryLlmPriceRepository(LlmScopeSupplier scope) {
        this.scope = scope;
        this.store = new ConcurrentHashMap<>();
    }

    @Override
    public void save(LlmPrice price) {
        namespace().put(price.id(), price);
    }

    @Override
    public Optional<LlmPrice> findById(LlmPriceId id) {
        return Optional.ofNullable(namespace().get(id));
    }

    @Override
    public List<LlmPrice> findByConfigName(String configName) {
        return namespace().values().stream().filter(p -> p.configName().equals(configName))
                .sorted(LlmPriceList.CHRONOLOGICAL).toList();
    }

    @Override
    public List<LlmPrice> findAll() {
        return namespace().values().stream().sorted(LlmPriceList.CHRONOLOGICAL).toList();
    }

    @Override
    public void deleteById(LlmPriceId id) {
        namespace().remove(id);
    }

    /** The entries of the namespace the scope names now, empty the first time it asks. */
    private Map<LlmPriceId, LlmPrice> namespace() {
        return store.computeIfAbsent(scope.namespace(), namespace -> new ConcurrentHashMap<>());
    }
}
