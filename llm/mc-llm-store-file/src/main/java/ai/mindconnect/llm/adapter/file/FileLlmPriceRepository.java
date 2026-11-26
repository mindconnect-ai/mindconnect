package ai.mindconnect.llm.adapter.file;

import ai.mindconnect.scope.TenantScope;
import ai.mindconnect.filerepo.Documents;
import ai.mindconnect.filerepo.FileRepo;
import ai.mindconnect.llm.management.api.domain.LlmPrice;
import ai.mindconnect.llm.management.api.domain.LlmPriceId;
import ai.mindconnect.llm.management.api.domain.LlmPriceList;
import ai.mindconnect.llm.management.api.port.out.LlmPriceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Stores LLM price periods under {@code {base}/{namespace}/system/llm-prices/{id}.json},
 * next to the configs they price — one small file per period.
 */
public class FileLlmPriceRepository implements LlmPriceRepository {

    private static final String DIR = "system/llm-prices";

    private final Documents<LlmPriceId, LlmPrice> prices;

    public FileLlmPriceRepository(Path storageDir, ObjectMapper objectMapper, TenantScope scope) {
        this.prices = Documents.of(LlmPrice.class)
                .path((LlmPriceId id) -> DIR + "/" + id.value() + ".json")
                .build(() -> FileRepo.open(storageDir, scope.tenant().namespace()), objectMapper);
    }

    @Override
    public void save(LlmPrice price) {
        prices.put(price.id(), price);
    }

    @Override
    public Optional<LlmPrice> findById(LlmPriceId id) {
        return prices.find(id).filter(price -> price.id().equals(id));
    }

    @Override
    public List<LlmPrice> findByConfigName(String configName) {
        return findAll().stream().filter(p -> p.configName().equals(configName)).toList();
    }

    @Override
    public List<LlmPrice> findAll() {
        return prices.findAll(DIR).stream().sorted(LlmPriceList.CHRONOLOGICAL).toList();
    }

    @Override
    public void deleteById(LlmPriceId id) {
        prices.delete(id);
    }
}
