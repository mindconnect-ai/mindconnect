package ai.mindconnect.agent.usermemory.adapter.memory;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.PerNamespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.usermemory.MemoryEntry;
import ai.mindconnect.agent.usermemory.UserMemoryRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link UserMemoryRepository} — process-lifetime storage, no persistence. */
public class InMemoryUserMemoryRepository implements UserMemoryRepository {

    private record Key(AgentId agentId, String name) { }

    private final PerNamespace<Map<UserId, Map<Key, MemoryEntry>>> store;

    public InMemoryUserMemoryRepository(ScopeSupplier scope) {
        this.store = new PerNamespace<>(scope, ConcurrentHashMap::new);
    }

    @Override
    public List<MemoryEntry> findByUser(UserId userId) {
        return List.copyOf(store.current().getOrDefault(userId, Map.of()).values());
    }

    @Override
    public List<MemoryEntry> findAll() {
        return store.current().values().stream().flatMap(m -> m.values().stream()).toList();
    }

    @Override
    public Optional<MemoryEntry> find(UserId userId, AgentId agentId, String name) {
        return Optional.ofNullable(store.current().getOrDefault(userId, Map.of()).get(new Key(agentId, name)));
    }

    @Override
    public MemoryEntry save(MemoryEntry entry) {
        store.current().computeIfAbsent(entry.userId(), u -> new ConcurrentHashMap<>())
                .put(new Key(entry.agentId(), entry.name()), entry);
        return entry;
    }

    @Override
    public boolean delete(UserId userId, AgentId agentId, String name) {
        Map<Key, MemoryEntry> entries = store.current().get(userId);
        return entries != null && entries.remove(new Key(agentId, name)) != null;
    }
}
