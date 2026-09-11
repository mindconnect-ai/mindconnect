package ai.mindconnect.agent.runtime.service;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentPatch;
import ai.mindconnect.agent.runtime.domain.AgentSpec;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.common.DomainException;

import java.util.List;
import java.util.Optional;

/**
 * Use-case service for {@link AgentDefinition} CRUD.
 *
 * <p>Stateless and thread-safe.
 */
public class AgentRegistryService {

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
     * Applies the patch to the agent. Absent patch fields are left as-is;
     * present fields overwrite. Throws {@code notFound} if the agent does
     * not exist.
     */
    public AgentDefinition update(AgentId agentId, AgentPatch patch) {
        AgentDefinition def = load(agentId);

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
        return definitionRepository.save(updated);
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
}
