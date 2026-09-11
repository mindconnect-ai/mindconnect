package ai.mindconnect.llm.adapter.file;

import ai.mindconnect.common.util.AtomicFiles;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.llm.domain.LlmConfigId;

import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class FileLlmConfigRepository implements LlmConfigRepository {

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    public FileLlmConfigRepository(Path storageDir, Namespace namespace) {
        this.baseDir = storageDir.resolve(namespace.value()).resolve("system").resolve("llm-configs");
        // Lenient on unknown fields so configs written by newer (or older)
        // versions still load — removed fields must never brick the store.
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void save(LlmConfig config) {
        try {
            AtomicFiles.write(fileFor(config.id().value()), out -> objectMapper.writeValue(out, config));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<LlmConfig> findById(LlmConfigId id) {
        Path file = fileFor(id.value());
        if (!Files.exists(file)) return Optional.empty();
        LlmConfig config = read(file);
        // The directory is flat: a file of another tenant with the same value is not this config.
        return config.id().equals(id) ? Optional.of(config) : Optional.empty();
    }

    @Override
    public Optional<LlmConfig> findByName(String name) {
        return findAll().stream().filter(c -> c.name().equals(name)).findFirst();
    }

    @Override
    public List<LlmConfig> findAll() {
        try (var stream = Files.list(baseDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(p -> read(p))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void deleteById(LlmConfigId id) {
        if (findById(id).isEmpty()) return;
        try {
            Files.deleteIfExists(fileFor(id.value()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A config written before the namespace was recorded takes the one asked for. */
    private LlmConfig read(Path file) {
        try {
            return objectMapper.readerFor(LlmConfig.class)
                    .readValue(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path fileFor(String id) {
        return baseDir.resolve(id + ".json");
    }
}
