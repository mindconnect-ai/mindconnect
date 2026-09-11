package ai.mindconnect.agent.runtime.memory.port.out;

import ai.mindconnect.agent.AuthenticationInfo;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;

import java.util.Optional;

/**
 * Persists and retrieves internal session data that is never agent-visible:
 * working memory snapshots and the conversation summary used for context
 * compression.
 *
 * <p>Keyed by {@link SessionId} alone. The id carries the tenant, and the
 * session it names carries the user — the {@code AuthenticationInfo} every
 * method used to take repeated both and could contradict the session.
 */
public interface WorkingMemoryRepository {

    void save(SessionId session, AuthenticationInfo auth, WorkingMemory memory);

    Optional<WorkingMemory> findBySession(SessionId session, AuthenticationInfo auth);

    void delete(SessionId session, AuthenticationInfo auth);

    // ── Summary ───────────────────────────────────────────────────────────────

    void saveSummary(SessionId session, AuthenticationInfo auth, String summary);

    Optional<String> loadSummary(SessionId session, AuthenticationInfo auth);

    void deleteSummary(SessionId session, AuthenticationInfo auth);
}
