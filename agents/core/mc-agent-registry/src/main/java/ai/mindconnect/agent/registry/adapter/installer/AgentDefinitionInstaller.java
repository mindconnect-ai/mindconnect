package ai.mindconnect.agent.registry.adapter.installer;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.registry.domain.ImportMode;
import ai.mindconnect.agent.registry.domain.ImportStatus;
import ai.mindconnect.agent.registry.domain.ImportedItem;
import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistryItemType;
import ai.mindconnect.agent.registry.port.out.RegistryInstaller;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentDefinitionStatus;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

/**
 * Installs an {@code agent} entry.
 *
 * <p>Like the LLM config, the agent is re-addressed on the way in: a new agent
 * gets a fresh {@link AgentId}, a re-import keeps the local id and version so
 * that sessions, sub-agent rosters and tool bindings still point at the same
 * agent. Timestamps are this installation's — {@code createdAt} is when the
 * agent arrived here, not when its author wrote it.
 *
 * <p>What is <em>not</em> checked is what the agent refers to. An imported
 * agent may name an LLM config or a sub-agent this installation does not have;
 * that is what an entry's {@code requires} and a package are for, and an agent
 * whose model is missing says so plainly on its own screen. Refusing the
 * import would only make the two halves of a package impossible to install in
 * any order.
 */
public class AgentDefinitionInstaller implements RegistryInstaller {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionInstaller.class);

    private final AgentDefinitionRepository repository;
    private final ObjectMapper objectMapper;

    public AgentDefinitionInstaller(AgentDefinitionRepository repository) {
        this.repository = repository;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public RegistryItemType type() {
        return RegistryItemType.AGENT;
    }

    @Override
    public boolean exists(String name) {
        return name != null && repository.findByName(name).isPresent();
    }

    @Override
    public ImportedItem install(RegistryEntry entry, String content, ImportMode mode) throws Exception {
        AgentDefinition incoming = objectMapper.readValue(content, AgentDefinition.class);
        String name = incoming.name() != null && !incoming.name().isBlank()
                ? incoming.name() : entry.name();
        Optional<AgentDefinition> existing = repository.findByName(name);

        if (existing.isPresent() && mode == ImportMode.SKIP_EXISTING) {
            return ImportedItem.skipped(entry, name, "an agent of this name is already here");
        }

        Instant now = Instant.now();
        AgentDefinition toSave = new AgentDefinition(
                existing.map(AgentDefinition::id).orElseGet(AgentId::random),
                name,
                incoming.description(),
                incoming.group(),
                incoming.icon(),
                incoming.systemPrompt(),
                incoming.welcomeMessage(),
                incoming.llmConfigName(),
                incoming.maxIterations() > 0 ? incoming.maxIterations() : 10,
                incoming.effectiveMemoryConfig(),
                incoming.status() != null ? incoming.status() : AgentDefinitionStatus.ACTIVE,
                incoming.tools(),
                incoming.responseReviewers(),
                incoming.callableAgents(),
                incoming.toolSearch(),
                existing.map(AgentDefinition::createdAt).orElse(now),
                now,
                existing.map(AgentDefinition::version).orElse(null));
        repository.save(toSave);
        log.info("Imported agent '{}' from registry entry '{}'", name, entry.id());

        return new ImportedItem(entry.id(), type(), name,
                existing.isPresent() ? ImportStatus.UPDATED : ImportStatus.IMPORTED, null);
    }
}
