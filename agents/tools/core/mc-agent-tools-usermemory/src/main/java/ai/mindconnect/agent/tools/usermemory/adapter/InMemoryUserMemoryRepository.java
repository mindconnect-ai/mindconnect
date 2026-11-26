package ai.mindconnect.agent.tools.usermemory.adapter;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.scope.NamespaceMap;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tools.usermemory.MemoryEntry;
import ai.mindconnect.agent.tools.usermemory.UserMemoryRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link UserMemoryRepository} — process-lifetime storage, no persistence. */
public class InMemoryUserMemoryRepository implements UserMemoryRepository {

    private record Key(AgentId agentId, String name) { }

    private final ScopeSupplier scope;
    private final NamespaceMap<Map<UserId, Map<Key, MemoryEntry>>> store;

    public InMemoryUserMemoryRepository(ScopeSupplier scope) {
        this.scope = scope;
        this.store = new NamespaceMap<>(ConcurrentHashMap::new);
    }

    @Override
    public List<MemoryEntry> findByUser(UserId userId) {
        return List.copyOf(store.of(scope.namespace()).getOrDefault(userId, Map.of()).values());
    }

    @Override
    public List<MemoryEntry> findAll() {
        return store.of(scope.namespace()).values().stream().flatMap(m -> m.values().stream()).toList();
    }

    @Override
    public Optional<MemoryEntry> find(UserId userId, AgentId agentId, String name) {
        return Optional.ofNullable(
                store.of(scope.namespace()).getOrDefault(userId, Map.of()).get(new Key(agentId, name)));
    }

    @Override
    public MemoryEntry save(MemoryEntry entry) {
        store.of(scope.namespace()).computeIfAbsent(entry.userId(), u -> new ConcurrentHashMap<>())
                .put(new Key(entry.agentId(), entry.name()), entry);
        return entry;
    }

    @Override
    public boolean delete(UserId userId, AgentId agentId, String name) {
        Map<Key, MemoryEntry> entries = store.of(scope.namespace()).get(userId);
        return entries != null && entries.remove(new Key(agentId, name)) != null;
    }
}
