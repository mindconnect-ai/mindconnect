package ai.mindconnect.llm.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.common.Versions;
import ai.mindconnect.filerepo.Documents;
import ai.mindconnect.filerepo.FileRepo;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Stores LLM configs under {@code {base}/{namespace}/system/llm-configs/{id}.json}.
 *
 * <p>{@link #save} checks the version a config carries against the stored one and
 * stores it one higher, both under the file's write lock.
 */
public class FileLlmConfigRepository implements LlmConfigRepository {

    private static final String DIR = "system/llm-configs";

    private final Documents<LlmConfigId, LlmConfig> configs;

    public FileLlmConfigRepository(Path storageDir, Namespace namespace) {
        // Lenient on unknown fields so configs written by newer (or older)
        // versions still load — removed fields must never brick the store.
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.configs = Documents.of(LlmConfig.class)
                .path((LlmConfigId id) -> DIR + "/" + id.value() + ".json")
                .build(FileRepo.open(storageDir, namespace.value()), objectMapper);
    }

    @Override
    public void save(LlmConfig config) {
        configs.compute(config.id(), current -> config.withVersion(Versions.next(
                current.map(LlmConfig::version).orElse(null), config.version(),
                "LlmConfig", config.id().value())));
    }

    @Override
    public Optional<LlmConfig> findById(LlmConfigId id) {
        // The directory is flat: a file of another tenant with the same value is not this config.
        return configs.find(id).filter(config -> config.id().equals(id));
    }

    @Override
    public Optional<LlmConfig> findByName(String name) {
        return findAll().stream().filter(c -> c.name().equals(name)).findFirst();
    }

    @Override
    public List<LlmConfig> findAll() {
        return configs.findAll(DIR);
    }

    @Override
    public void deleteById(LlmConfigId id) {
        configs.delete(id);
    }
}
