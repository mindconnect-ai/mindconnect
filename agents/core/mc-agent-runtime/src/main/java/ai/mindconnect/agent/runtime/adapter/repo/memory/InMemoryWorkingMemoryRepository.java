package ai.mindconnect.agent.runtime.adapter.repo.memory;

import ai.mindconnect.agent.SessionId;

import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.AuthenticationInfo;
import ai.mindconnect.agent.PerNamespace;
import ai.mindconnect.agent.ScopeSupplier;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link WorkingMemoryRepository} — process-lifetime storage, no persistence. Keyed by
 * session id (the {@link AuthenticationInfo} is not needed to disambiguate in-process).
 */
public class InMemoryWorkingMemoryRepository implements WorkingMemoryRepository {

    private final PerNamespace<Map<SessionId, WorkingMemory>> memories;
    private final PerNamespace<Map<SessionId, String>> summaries;

    public InMemoryWorkingMemoryRepository(ScopeSupplier scope) {
        this.memories = new PerNamespace<>(scope, ConcurrentHashMap::new);
        this.summaries = new PerNamespace<>(scope, ConcurrentHashMap::new);
    }

    @Override
    public void save(SessionId sessionId, AuthenticationInfo auth, WorkingMemory memory) {
        memories.current().put(sessionId, memory);
    }

    @Override
    public Optional<WorkingMemory> findBySession(SessionId sessionId, AuthenticationInfo auth) {
        return Optional.ofNullable(memories.current().get(sessionId));
    }

    @Override
    public void delete(SessionId sessionId, AuthenticationInfo auth) {
        memories.current().remove(sessionId);
    }

    @Override
    public void saveSummary(SessionId sessionId, AuthenticationInfo auth, String summary) {
        summaries.current().put(sessionId, summary);
    }

    @Override
    public Optional<String> loadSummary(SessionId sessionId, AuthenticationInfo auth) {
        return Optional.ofNullable(summaries.current().get(sessionId));
    }

    @Override
    public void deleteSummary(SessionId sessionId, AuthenticationInfo auth) {
        summaries.current().remove(sessionId);
    }
}
