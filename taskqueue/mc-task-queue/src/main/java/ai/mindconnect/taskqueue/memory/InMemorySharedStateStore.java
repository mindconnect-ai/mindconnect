package ai.mindconnect.taskqueue.memory;

import ai.mindconnect.taskqueue.SharedStateStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reference store: a map of maps. The claim's atomicity comes from
 * {@link ConcurrentHashMap#putIfAbsent} — the same guarantee a database store
 * gets from {@code INSERT … ON CONFLICT DO NOTHING}, which is why no lock is
 * held across the two halves of a check-and-write.
 */
public final class InMemorySharedStateStore implements SharedStateStore {

    private final Map<String, Map<String, Object>> byId = new ConcurrentHashMap<>();

    @Override
    public boolean putIfAbsent(String id, String key, Object value) {
        return scope(id).putIfAbsent(key, value) == null;
    }

    @Override
    public void put(String id, String key, Object value) {
        scope(id).put(key, value);
    }

    @Override
    public Optional<Object> get(String id, String key) {
        return Optional.ofNullable(scope(id).get(key));
    }

    @Override
    public Map<String, Object> all(String id) {
        return new LinkedHashMap<>(scope(id));
    }

    @Override
    public int clear(String id) {
        Map<String, Object> gone = byId.remove(id);
        return gone == null ? 0 : gone.size();
    }

    private Map<String, Object> scope(String id) {
        return byId.computeIfAbsent(id, key -> new ConcurrentHashMap<>());
    }
}
