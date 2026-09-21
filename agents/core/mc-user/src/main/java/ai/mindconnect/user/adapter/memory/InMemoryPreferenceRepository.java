package ai.mindconnect.user.adapter.memory;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.Preferences;
import ai.mindconnect.user.port.out.PreferenceRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link PreferenceRepository} in a map — for tests, and for a host that keeps
 * nothing between restarts. A screen that forgets where it was after a
 * restart is a small loss.
 */
public class InMemoryPreferenceRepository implements PreferenceRepository {

    private record Key(UserId userId, String scope) { }

    private final Map<Key, Preferences> byKey = new ConcurrentHashMap<>();

    @Override
    public Optional<Preferences> find(UserId userId, String scope) {
        return Optional.ofNullable(byKey.get(new Key(userId, scope)));
    }

    @Override
    public List<Preferences> findByUser(UserId userId) {
        return byKey.values().stream().filter(p -> p.userId().equals(userId)).toList();
    }

    @Override
    public void save(Preferences preferences) {
        byKey.put(new Key(preferences.userId(), preferences.scope()), preferences);
    }

    @Override
    public void delete(UserId userId, String scope) {
        byKey.remove(new Key(userId, scope));
    }
}
