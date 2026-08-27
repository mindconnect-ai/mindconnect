package ai.mindconnect.agent.port.in;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentPatch;
import ai.mindconnect.agent.domain.AgentSpec;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Design-time use cases for {@link AgentDefinition} management.
 *
 * <p>Instances are bound to a {@code (AuthenticationInfo, Namespace)} pair at
 * construction. Methods therefore take no auth or tenant arguments — the
 * binding is implicit. Authorization, validation and default-tool seeding
 * are policy of this port; the underlying repository remains identity-agnostic.
 *
 * <p>Separate from {@link AgentRuntime} because the two have distinct
 * consumers: an agent editor only needs this port; a chat UI only needs
 * the runtime.
 */
public interface AgentRegistry {

    /**
     * Creates a new {@link AgentDefinition} in the bound namespace. Implementations
     * are expected to validate {@code spec} (e.g. non-blank name), assign an id,
     * and seed any default tools.
     */
    AgentDefinition create(AgentSpec spec);

    /**
     * Applies the given patch to the agent identified by {@code agentId}. Only
     * fields present in the patch are changed; absent fields are left as-is.
     * Throws if the agent does not exist or is not visible in the bound namespace.
     */
    AgentDefinition update(UUID agentId, AgentPatch patch);

    /** Returns the agent if it exists and is visible in the bound namespace. */
    Optional<AgentDefinition> find(UUID agentId);

    /** Lists all agents in the bound namespace. */
    List<AgentDefinition> list();

    /** Deletes the agent. No-op if it does not exist. */
    void delete(UUID agentId);
}
