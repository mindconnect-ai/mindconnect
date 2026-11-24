package ai.mindconnect.agent.registry.adapter.installer;

import ai.mindconnect.agent.registry.domain.ImportMode;
import ai.mindconnect.agent.registry.domain.ImportStatus;
import ai.mindconnect.agent.registry.domain.ImportedItem;
import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistryItemType;
import ai.mindconnect.agent.registry.port.out.RegistryInstaller;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.format.AgentJson;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.capabilities.review.ResponseReviewPlugin;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillPlugin;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
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

    private final AgentManagement agents;
    private final AgentJson files;

    public AgentDefinitionInstaller(AgentManagement agents) {
        this.agents = agents;
        this.files = new AgentJson(new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    }

    @Override
    public RegistryItemType type() {
        return RegistryItemType.AGENT;
    }

    @Override
    public boolean exists(String name) {
        return name != null && agents.findByName(name).isPresent();
    }

    /**
     * Agents running on that LLM config, calling or reviewing with that agent,
     * or naming that skill. An agent that loads every skill is not listed:
     * it loads whatever is here and misses nothing by name.
     */
    @Override
    public List<String> referencesTo(RegistryItemType type, String name) {
        if (name == null) {
            return List.of();
        }
        return agents.list().stream()
                .map(ManagedAgent::definition)
                .filter(agent -> switch (type) {
                    case LLM_CONFIG -> name.equals(agent.modelSettings().model());
                    case AGENT -> !name.equals(agent.name())
                            && (SubAgentPlugin.subAgentsOf(agent).contains(name)
                                    || ResponseReviewPlugin.reviewersOf(agent).contains(name));
                    case SKILL -> SkillPlugin.skillsOf(agent).map(picked -> picked.contains(name)).orElse(false);
                    default -> false;
                })
                .map(AgentDefinition::name)
                .toList();
    }

    @Override
    public ImportedItem remove(RegistryEntry entry) {
        Optional<ManagedAgent> existing = agents.findByName(entry.name());
        if (existing.isEmpty()) {
            return ImportedItem.skipped(entry, entry.name(), "no agent of this name is here");
        }
        agents.delete(existing.get().id());
        log.info("Removed agent '{}' (registry entry '{}')", entry.name(), entry.id());
        return ImportedItem.removed(entry, entry.name());
    }

    @Override
    public ImportedItem install(RegistryEntry entry, String content, ImportMode mode) throws Exception {
        ManagedAgent incoming = files.read(withName(content, entry.name()));
        String name = incoming.definition().name();
        Optional<ManagedAgent> existing = agents.findByName(name);

        if (existing.isPresent() && mode == ImportMode.SKIP_EXISTING) {
            return ImportedItem.skipped(entry, name, "an agent of this name is already here");
        }
        if (existing.isPresent()) {
            agents.update(existing.get().id(), existing.get().version(), stored -> incoming);
        } else {
            // A new agent gets an id of this installation: the author's may be taken here.
            agents.create(new ManagedAgent(null, incoming.definition(), incoming.group(), incoming.icon(),
                    incoming.welcomeMessage(), incoming.status(), null, null, 0));
        }
        log.info("Imported agent '{}' from registry entry '{}'", name, entry.id());

        return new ImportedItem(entry.id(), type(), name,
                existing.isPresent() ? ImportStatus.UPDATED : ImportStatus.IMPORTED, null);
    }

    /** The file's text, named after its registry entry when it names no agent itself. */
    private static String withName(String content, String entryName) throws Exception {
        ObjectMapper plain = new ObjectMapper();
        JsonNode agent = plain.readTree(content);
        if (!(agent instanceof ObjectNode fields)) {
            return content;
        }
        JsonNode holder = fields.has("definition") ? fields.get("definition") : fields;
        if (holder instanceof ObjectNode named && !(named.hasNonNull("name") && !named.get("name").asText().isBlank())) {
            named.put("name", entryName);
        }
        return plain.writeValueAsString(fields);
    }
}
