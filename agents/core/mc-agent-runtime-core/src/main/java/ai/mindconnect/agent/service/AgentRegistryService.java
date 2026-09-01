package ai.mindconnect.agent.service;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentPatch;
import ai.mindconnect.agent.domain.AgentSpec;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.common.DomainException;
import ai.mindconnect.common.Namespace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Use-case service for {@link AgentDefinition} CRUD.
 *
 * <p>Stateless and thread-safe. Every method takes the {@link Namespace} the
 * caller operates in. The composite {@code (namespace, agentId)} is the
 * domain's logical primary key — an agent can be copied into another
 * namespace and keep the same id. The service enforces this by validating
 * that any agent loaded by id actually belongs to the requested namespace;
 * cross-tenant access surfaces as {@code notFound}.
 *
 * <p>Until the outbound ports are migrated to a composite-key lookup (see
 * follow-up task), the namespace check is performed here on top of the
 * existing {@code findById(UUID)} repository methods.
 */
public class AgentRegistryService {

    private final AgentDefinitionRepository definitionRepository;

    public AgentRegistryService(AgentDefinitionRepository definitionRepository) {
        this.definitionRepository = definitionRepository;
    }

    /**
     * Creates a new agent in the given namespace. Validates the spec
     * (non-blank name) and seeds the default workspace tools.
     */
    public AgentDefinition create(Namespace namespace, AgentSpec spec) {
        if (spec.name() == null || spec.name().isBlank()) {
            throw DomainException.invalid("AgentSpec.name must not be blank");
        }
        AgentDefinition def = AgentDefinition.create(namespace,
                spec.name(), spec.description(), spec.systemPrompt(),
                spec.welcomeMessage(), spec.llmConfigName());
        def = def.withTools(List.of(
                AgentTool.of(def.id(), "workspace_read",
                        "Reads a file from the agent workspace (session, agent, or user scope)."),
                AgentTool.of(def.id(), "workspace_write",
                        "Writes a file to the agent workspace (session or agent scope)."),
                AgentTool.of(def.id(), "workspace_list",
                        "Lists files in a workspace scope (session, agent, or user).")
        ));
        return definitionRepository.save(def);
    }

    /**
     * Applies the patch to the agent identified by {@code (namespace, agentId)}.
     * Absent patch fields are left as-is; present fields overwrite. Throws
     * {@code notFound} if the agent does not exist in the namespace.
     */
    public AgentDefinition update(Namespace namespace, UUID agentId, AgentPatch patch) {
        AgentDefinition def = loadInNamespace(namespace, agentId);

        AgentDefinition updated = def.withBasicFields(
                patch.namespace().orElse(def.namespace()),
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
     * Duplicates the agent identified by {@code (namespace, agentId)} —
     * "{name}-copy", fresh id, no tools (they carry per-agent ids). Throws
     * {@code notFound} if the agent does not exist in the namespace.
     */
    public AgentDefinition copy(Namespace namespace, UUID agentId) {
        return definitionRepository.save(loadInNamespace(namespace, agentId).asCopy());
    }

    /**
     * Returns the agent if it exists in the given namespace, else empty.
     * An agent with the same id in a different namespace is treated as
     * non-existent from this caller's point of view.
     */
    public Optional<AgentDefinition> find(Namespace namespace, UUID agentId) {
        return definitionRepository.findById(agentId)
                .filter(def -> def.namespace().equals(namespace));
    }

    public List<AgentDefinition> list(Namespace namespace) {
        return definitionRepository.findByNamespace(namespace);
    }

    /**
     * Deletes the agent identified by {@code (namespace, agentId)}. No-op if
     * the agent does not exist in the namespace.
     */
    public void delete(Namespace namespace, UUID agentId) {
        find(namespace, agentId).ifPresent(def -> definitionRepository.deleteById(def.id()));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private AgentDefinition loadInNamespace(Namespace namespace, UUID agentId) {
        return find(namespace, agentId)
                .orElseThrow(() -> DomainException.notFound("AgentDefinition", agentId.toString()));
    }
}
