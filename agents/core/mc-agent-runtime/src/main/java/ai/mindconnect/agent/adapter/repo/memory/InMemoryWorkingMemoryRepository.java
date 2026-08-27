package ai.mindconnect.agent.adapter.repo.memory;

import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.agent.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.common.AuthenticationInfo;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link WorkingMemoryRepository} — process-lifetime storage, no persistence. Keyed by
 * session id (the {@link AuthenticationInfo} is not needed to disambiguate in-process).
 */
public class InMemoryWorkingMemoryRepository implements WorkingMemoryRepository {

    private final Map<UUID, WorkingMemory> memories = new ConcurrentHashMap<>();
    private final Map<UUID, String> summaries = new ConcurrentHashMap<>();

    @Override
    public void save(UUID sessionId, AuthenticationInfo auth, WorkingMemory memory) {
        memories.put(sessionId, memory);
    }

    @Override
    public Optional<WorkingMemory> findBySessionId(UUID sessionId, AuthenticationInfo auth) {
        return Optional.ofNullable(memories.get(sessionId));
    }

    @Override
    public void delete(UUID sessionId, AuthenticationInfo auth) {
        memories.remove(sessionId);
    }

    @Override
    public void saveSummary(UUID sessionId, AuthenticationInfo auth, String summary) {
        summaries.put(sessionId, summary);
    }

    @Override
    public Optional<String> loadSummary(UUID sessionId, AuthenticationInfo auth) {
        return Optional.ofNullable(summaries.get(sessionId));
    }

    @Override
    public void deleteSummary(UUID sessionId, AuthenticationInfo auth) {
        summaries.remove(sessionId);
    }
}
