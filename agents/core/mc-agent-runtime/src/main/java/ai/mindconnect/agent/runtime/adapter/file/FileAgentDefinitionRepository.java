package ai.mindconnect.agent.runtime.adapter.file;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.common.Versions;
import ai.mindconnect.filerepo.Documents;
import ai.mindconnect.filerepo.FileRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Stores agent definitions under {@code {base}/{namespace}/system/agents/{id}.json}.
 *
 * <p>{@link #save} checks the version a definition carries against the stored one
 * and stores it one higher, both under the file's write lock — two edits of the
 * same agent cannot both pass the check.
 */
public class FileAgentDefinitionRepository implements AgentDefinitionRepository {

    private static final Logger log = LoggerFactory.getLogger(FileAgentDefinitionRepository.class);
    private static final String DIR = "system/agents";

    private final Documents<AgentId, AgentDefinition> definitions;

    public FileAgentDefinitionRepository(Path agentStorageDir, ObjectMapper objectMapper, ScopeSupplier scope) {
        this.definitions = Documents.of(AgentDefinition.class)
                .path((AgentId id) -> DIR + "/" + id.value() + ".json")
                .build(() -> FileRepo.open(agentStorageDir, scope.namespace().value()), objectMapper);
        log.info("AgentDefinitionRepository storage: {}", agentStorageDir.resolve("<namespace>").resolve(DIR));
    }

    @Override
    public AgentDefinition save(AgentDefinition def) {
        return definitions.compute(def.id(), current -> def.withVersion(Versions.next(
                current.map(AgentDefinition::version).orElse(null), def.version(),
                "AgentDefinition", def.id().value())));
    }

    @Override
    public Optional<AgentDefinition> findById(AgentId id) {
        // The directory is flat: a file of another tenant with the same value is not this agent.
        return definitions.find(id).filter(def -> def.id().equals(id));
    }

    @Override
    public List<AgentDefinition> findAll() {
        return definitions.findAll(DIR);
    }

    @Override
    public Optional<AgentDefinition> findByName(String name) {
        return findAll().stream()
                .filter(d -> d.name().equalsIgnoreCase(name))
                .findFirst();
    }

    @Override
    public void deleteById(AgentId id) {
        definitions.delete(id);
    }
}
