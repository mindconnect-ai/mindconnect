package ai.mindconnect.llm.adapter.file;

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
import java.util.UUID;

public class FileLlmConfigRepository implements LlmConfigRepository {

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    public FileLlmConfigRepository(Path storageDir) {
        this.baseDir = storageDir.resolve("system").resolve("llm-configs");
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
            objectMapper.writeValue(fileFor(config.id()).toFile(), config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<LlmConfig> findById(UUID id) {
        Path file = fileFor(id);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), LlmConfig.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<LlmConfig> findByName(String name) {
        try (var stream = Files.list(baseDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(p -> {
                        try {
                            return objectMapper.readValue(p.toFile(), LlmConfig.class);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .filter(c -> c.name().equals(name))
                    .findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<LlmConfig> findAll() {
        try (var stream = Files.list(baseDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(p -> {
                        try {
                            return objectMapper.readValue(p.toFile(), LlmConfig.class);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void deleteById(UUID id) {
        try {
            Files.deleteIfExists(fileFor(id));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path fileFor(UUID id) {
        return baseDir.resolve(id + ".json");
    }
}
