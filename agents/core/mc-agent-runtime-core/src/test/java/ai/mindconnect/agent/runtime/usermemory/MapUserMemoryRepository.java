package ai.mindconnect.agent.runtime.usermemory;

import ai.mindconnect.agent.UserId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The smallest repository that works — the in-memory adapter lives in a module above this one. */
class MapUserMemoryRepository implements UserMemoryRepository {

    private final Map<String, MemoryEntry> entries = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public List<MemoryEntry> findByUser(UserId userId) {
        return new ArrayList<>(entries.values().stream().filter(e -> e.userId().equals(userId)).toList());
    }

    @Override
    public Optional<MemoryEntry> find(UserId userId, String name) {
        return Optional.ofNullable(entries.get(userId.value() + "/" + name));
    }

    @Override
    public MemoryEntry save(MemoryEntry entry) {
        entries.put(entry.userId().value() + "/" + entry.name(), entry);
        return entry;
    }

    @Override
    public boolean delete(UserId userId, String name) {
        return entries.remove(userId.value() + "/" + name) != null;
    }
}
