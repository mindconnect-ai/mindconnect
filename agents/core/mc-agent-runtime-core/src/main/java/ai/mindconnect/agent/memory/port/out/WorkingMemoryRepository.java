package ai.mindconnect.agent.memory.port.out;

import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.common.AuthenticationInfo;

import java.util.Optional;
import java.util.UUID;

/**
 * Persists and retrieves internal session data that is never agent-visible:
 * working memory snapshots and the conversation summary used for context compression.
 *
 * All methods receive {@link AuthenticationInfo} explicitly — no in-memory index required.
 */
public interface WorkingMemoryRepository {

    void save(UUID sessionId, AuthenticationInfo auth, WorkingMemory memory);

    Optional<WorkingMemory> findBySessionId(UUID sessionId, AuthenticationInfo auth);

    void delete(UUID sessionId, AuthenticationInfo auth);

    // ── Summary ───────────────────────────────────────────────────────────────

    void saveSummary(UUID sessionId, AuthenticationInfo auth, String summary);

    Optional<String> loadSummary(UUID sessionId, AuthenticationInfo auth);

    void deleteSummary(UUID sessionId, AuthenticationInfo auth);
}
