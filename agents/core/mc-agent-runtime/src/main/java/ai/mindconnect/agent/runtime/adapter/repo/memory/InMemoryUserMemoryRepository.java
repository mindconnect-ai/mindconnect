package ai.mindconnect.agent.runtime.adapter.repo.memory;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.usermemory.MemoryEntry;
import ai.mindconnect.agent.runtime.usermemory.UserMemoryRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link UserMemoryRepository} — process-lifetime storage, no persistence. */
public class InMemoryUserMemoryRepository implements UserMemoryRepository {

    private final Map<UserId, Map<String, MemoryEntry>> store = new ConcurrentHashMap<>();

    @Override
    public List<MemoryEntry> findByUser(UserId userId) {
        return List.copyOf(store.getOrDefault(userId, Map.of()).values());
    }

    @Override
    public Optional<MemoryEntry> find(UserId userId, String name) {
        return Optional.ofNullable(store.getOrDefault(userId, Map.of()).get(name));
    }

    @Override
    public MemoryEntry save(MemoryEntry entry) {
        store.computeIfAbsent(entry.userId(), u -> new ConcurrentHashMap<>()).put(entry.name(), entry);
        return entry;
    }

    @Override
    public boolean delete(UserId userId, String name) {
        Map<String, MemoryEntry> entries = store.get(userId);
        return entries != null && entries.remove(name) != null;
    }
}
