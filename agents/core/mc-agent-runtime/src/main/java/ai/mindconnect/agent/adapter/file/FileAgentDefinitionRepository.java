package ai.mindconnect.agent.adapter.file;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.common.Namespace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

@Component
@ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "file", matchIfMissing = true)
public class FileAgentDefinitionRepository implements AgentDefinitionRepository {

    private static final Logger log = Logger.getLogger(FileAgentDefinitionRepository.class.getName());

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    public FileAgentDefinitionRepository(Path agentStorageDir, ObjectMapper objectMapper) {
        this.baseDir = agentStorageDir.resolve("system").resolve("agents").toAbsolutePath();
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
            objectMapper.writeValue(fileFor(def.id()).toFile(), def);
            return def;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<AgentDefinition> findById(UUID id) {
        Path file = fileFor(id);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), AgentDefinition.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<AgentDefinition> findByNamespace(Namespace namespace) {
        try (var stream = Files.list(baseDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(p -> {
                        try {
                            return objectMapper.readValue(p.toFile(), AgentDefinition.class);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .filter(d -> d.namespace().equals(namespace))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<AgentDefinition> findByName(Namespace namespace, String name) {
        return findByNamespace(namespace).stream()
                .filter(d -> d.name().equalsIgnoreCase(name))
                .findFirst();
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
