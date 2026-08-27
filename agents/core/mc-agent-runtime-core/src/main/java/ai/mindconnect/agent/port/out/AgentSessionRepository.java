package ai.mindconnect.agent.port.out;

import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.common.Namespace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentSessionRepository {

    AgentSession save(AgentSession session);

    Optional<AgentSession> findById(UUID id);

    List<AgentSession> findByAgentDefinitionId(UUID agentDefinitionId, Namespace namespace, String userId);

    /**
     * Returns every session whose {@code parentSessionId} equals
     * {@code parentSessionId} — i.e. all sub-agent sessions directly
     * spawned by the given session. Empty list for top-level sessions
     * that never invoked {@code run_agent}.
     */
    List<AgentSession> findByParentSessionId(UUID parentSessionId);

    /** Deletes the session directory and all its contents. No-op if not found. */
    void deleteById(UUID id);
}
