package ai.mindconnect.agent.runtime.usermemory;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The smallest repository that works — the in-memory adapter lives in a module above this one. */
class MapUserMemoryRepository implements UserMemoryRepository {

    private final Map<String, MemoryEntry> entries = new java.util.concurrent.ConcurrentHashMap<>();

    private static String key(UserId userId, AgentId agentId, String name) {
        return userId.value() + "/" + (agentId == null ? "" : "@" + agentId.value() + "/") + name;
    }

    @Override
    public List<MemoryEntry> findByUser(UserId userId) {
        return new ArrayList<>(entries.values().stream().filter(e -> e.userId().equals(userId)).toList());
    }

    @Override
    public List<MemoryEntry> findAll() {
        return new ArrayList<>(entries.values());
    }

    @Override
    public Optional<MemoryEntry> find(UserId userId, AgentId agentId, String name) {
        return Optional.ofNullable(entries.get(key(userId, agentId, name)));
    }

    @Override
    public MemoryEntry save(MemoryEntry entry) {
        entries.put(key(entry.userId(), entry.agentId(), entry.name()), entry);
        return entry;
    }

    @Override
    public boolean delete(UserId userId, AgentId agentId, String name) {
        return entries.remove(key(userId, agentId, name)) != null;
    }
}
