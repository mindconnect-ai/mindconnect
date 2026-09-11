package ai.mindconnect.agent.runtime.service;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentPatch;
import ai.mindconnect.agent.runtime.domain.AgentSpec;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.common.DomainException;
import ai.mindconnect.common.StaleVersionException;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Use-case service for {@link AgentDefinition} CRUD.
 *
 * <p>Stateless and thread-safe. Saves are versioned (see
 * {@code ai.mindconnect.common.Versions}): an edit form passes the version it
 * was opened with and is refused when the agent was saved since; a change
 * that carries no version is applied to the agent as stored and re-applied if
 * another save came in between.
 */
public class AgentRegistryService {

    /** How often a change without a version is re-applied when another save came in between. */
    private static final int ATTEMPTS = 3;

    private final AgentDefinitionRepository definitionRepository;

    public AgentRegistryService(AgentDefinitionRepository definitionRepository) {
        this.definitionRepository = definitionRepository;
    }

    /**
     * Creates a new agent. Validates the spec
     * (non-blank name) and seeds the default workspace tools.
     */
    public AgentDefinition create(AgentSpec spec) {
        if (spec.name() == null || spec.name().isBlank()) {
            throw DomainException.invalid("AgentSpec.name must not be blank");
        }
        AgentDefinition def = AgentDefinition.create(spec.name(), spec.description(), spec.systemPrompt(),
                spec.welcomeMessage(), spec.llmConfigName());
        def = def.withTools(List.of(
                AgentTool.of("workspace_read",
                        "Reads a file from the agent workspace (session, agent, or user scope)."),
                AgentTool.of("workspace_write",
                        "Writes a file to the agent workspace (session or agent scope)."),
                AgentTool.of("workspace_list",
                        "Lists files in a workspace scope (session, agent, or user).")
        ));
        return definitionRepository.save(def);
    }

    /**
     * Applies the patch to the agent as it is stored now. Absent patch fields are
     * left as-is; present fields overwrite. When another save lands between reading
     * and writing, the patch is applied again to the newer agent. Throws
     * {@code notFound} if the agent does not exist.
     */
    public AgentDefinition update(AgentId agentId, AgentPatch patch) {
        return retrying(() -> update(agentId, patch, null));
    }

    /**
     * Applies the patch to the agent the caller read at {@code expectedVersion} — an
     * edit form's save. With {@code null} nothing is checked and nothing retried.
     *
     * @throws StaleVersionException when the agent was saved since; nothing is written
     */
    public AgentDefinition update(AgentId agentId, AgentPatch patch, Long expectedVersion) {
        AgentDefinition def = load(agentId);
        long stored = def.version() == null ? 0 : def.version();
        if (expectedVersion != null && expectedVersion != stored) {
            throw new StaleVersionException("AgentDefinition", agentId.value(), expectedVersion, stored);
        }

        AgentDefinition updated = def.withBasicFields(
                patch.name().orElse(def.name()),
                patch.description().orElse(def.description()),
                patch.systemPrompt().orElse(def.systemPrompt()),
                patch.welcomeMessage().orElse(def.welcomeMessage()),
                patch.llmConfigName().orElse(def.llmConfigName()),
                patch.maxIterations().orElse(def.maxIterations()),
                patch.responseReviewers().orElse(def.responseReviewers()));
        if (patch.group().isPresent()) {
            updated = updated.withGroup(patch.group().get());
        }
        if (patch.icon().isPresent()) {
            updated = updated.withIcon(patch.icon().get());
        }
        if (patch.callableAgents().isPresent()) {
            updated = updated.withCallableAgents(patch.callableAgents().get());
        }
        if (patch.toolSearch().isPresent()) {
            updated = updated.withToolSearch(patch.toolSearch().get());
        }
        if (patch.tools().isPresent()) {
            updated = updated.withTools(patch.tools().get());
        }
        if (patch.memoryConfig().isPresent()) {
            updated = updated.withMemoryConfig(patch.memoryConfig().get());
        }
        // Still carries the version read above: the store refuses it if a save landed since.
        return definitionRepository.save(updated);
    }

    /**
     * Replaces the agent's tools with what {@code change} makes of the stored list —
     * adding, editing or removing one tool without dropping a tool that was added
     * meanwhile. {@code change} may run more than once.
     */
    public AgentDefinition updateTools(AgentId agentId, UnaryOperator<List<AgentTool>> change) {
        return retrying(() -> {
            AgentDefinition def = load(agentId);
            List<AgentTool> tools = def.tools() == null ? List.of() : def.tools();
            return definitionRepository.save(def.withTools(change.apply(tools)));
        });
    }

    /**
     * Duplicates the agent — "{name}-copy", fresh id, no tools (they carry
     * per-agent ids). Throws {@code notFound} if the agent does not exist.
     */
    public AgentDefinition copy(AgentId agentId) {
        return definitionRepository.save(load(agentId).asCopy());
    }

    public Optional<AgentDefinition> find(AgentId agentId) {
        return definitionRepository.findById(agentId);
    }

    public List<AgentDefinition> list() {
        return definitionRepository.findAll();
    }

    /** Deletes the agent. No-op if it does not exist. */
    public void delete(AgentId agentId) {
        find(agentId).ifPresent(def -> definitionRepository.deleteById(def.id()));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private AgentDefinition load(AgentId agentId) {
        return find(agentId)
                .orElseThrow(() -> DomainException.notFound("AgentDefinition", agentId.toString()));
    }

    private static AgentDefinition retrying(Supplier<AgentDefinition> save) {
        for (int attempt = 1; ; attempt++) {
            try {
                return save.get();
            } catch (StaleVersionException e) {
                if (attempt >= ATTEMPTS) throw e;
            }
        }
    }
}
