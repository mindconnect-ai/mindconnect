package ai.mindconnect.agent.runtime.adapter.file;

import ai.mindconnect.common.util.AtomicFiles;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.AgentId;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

public class FileAgentDefinitionRepository implements AgentDefinitionRepository {

    private static final Logger log = Logger.getLogger(FileAgentDefinitionRepository.class.getName());

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    public FileAgentDefinitionRepository(Path agentStorageDir, ObjectMapper objectMapper, Namespace namespace) {
        this.baseDir = agentStorageDir.resolve(namespace.value()).resolve("system").resolve("agents").toAbsolutePath();
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        log.info("AgentDefinitionRepository storage: " + this.baseDir);
    }

    @Override
    public AgentDefinition save(AgentDefinition def) {
        try {
            AtomicFiles.write(fileFor(def.id().value()), out -> objectMapper.writeValue(out, def));
            return def;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<AgentDefinition> findById(AgentId id) {
        Path file = fileFor(id.value());
        if (!Files.exists(file)) return Optional.empty();
        try {
            AgentDefinition def = read(file, AgentDefinition.class);
            // The directory is flat: a file of another tenant with the same value is not this agent.
            return def.id().equals(id) ? Optional.of(def) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<AgentDefinition> findAll() {
        try (var stream = Files.list(baseDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(p -> {
                        try {
                            return read(p, AgentDefinition.class);
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
    public Optional<AgentDefinition> findByName(String name) {
        return findAll().stream()
                .filter(d -> d.name().equalsIgnoreCase(name))
                .findFirst();
    }

    @Override
    public void deleteById(AgentId id) {
        if (findById(id).isEmpty()) return;
        try {
            Files.deleteIfExists(fileFor(id.value()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path fileFor(String id) {
        return baseDir.resolve(id + ".json");
    }

    /** Reads a document in {@code namespace}; one written before the namespace was recorded takes it from here. */
    private <T> T read(Path file, Class<T> type) throws IOException {
        return objectMapper.readerFor(type)
                .readValue(file.toFile());
    }
}
