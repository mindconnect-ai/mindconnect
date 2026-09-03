package ai.mindconnect.agent.port.out;

import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.view.AgentSessionHeader;
import ai.mindconnect.common.Namespace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentSessionRepository {

    AgentSession save(AgentSession session);

    Optional<AgentSession> findById(UUID id);

    List<AgentSession> findByAgentDefinitionId(UUID agentDefinitionId, Namespace namespace, String userId);

    /**
     * Every top-level session of one user, newest first — the chat's session
     * list. Sub-agent sessions ({@code parentSessionId != null}) are left out:
     * they belong to the turn that spawned them, not to the user's history.
     */
    List<AgentSession> findByUser(Namespace namespace, String userId);

    /**
     * The same sessions as {@link #findByUser}, as headers. A store that
     * keeps the header's fields beside the document answers this without
     * reading a single document; the default simply serves the full
     * sessions, which are headers too.
     */
    default List<? extends AgentSessionHeader> findHeadersByUser(Namespace namespace, String userId) {
        return findByUser(namespace, userId);
    }

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
