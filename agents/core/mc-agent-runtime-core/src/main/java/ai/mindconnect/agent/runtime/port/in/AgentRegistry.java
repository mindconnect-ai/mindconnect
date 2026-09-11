package ai.mindconnect.agent.runtime.port.in;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.runtime.domain.AgentPatch;
import ai.mindconnect.agent.runtime.domain.AgentSpec;

import java.util.List;
import java.util.Optional;

/**
 * Design-time use cases for {@link AgentDefinition} management.
 *
 * <p>Instances are bound to an {@code AuthenticationInfo} at construction.
 * Methods therefore take no auth arguments — the binding is implicit. Authorization, validation and
 * default-tool seeding are policy of this port; the underlying repository
 * remains identity-agnostic.
 *
 * <p>Agents are addressed by their {@link AgentId}, like everywhere else.
 *
 * <p>Separate from {@link AgentRuntime} because the two have distinct
 * consumers: an agent editor only needs this port; a chat UI only needs
 * the runtime.
 */
public interface AgentRegistry {

    /**
     * Creates a new {@link AgentDefinition}. Implementations
     * are expected to validate {@code spec} (e.g. non-blank name), assign an id,
     * and seed any default tools.
     */
    AgentDefinition create(AgentSpec spec);

    /**
     * Applies the patch to the agent. Only fields present in the patch are
     * changed. Throws if the agent does not exist.
     */
    AgentDefinition update(AgentId agentId, AgentPatch patch);

    /** The agent, if it exists. */
    Optional<AgentDefinition> find(AgentId agentId);

    /** Lists all agents. */
    List<AgentDefinition> list();

    /** Deletes the agent. No-op if it does not exist. */
    void delete(AgentId agentId);
}
