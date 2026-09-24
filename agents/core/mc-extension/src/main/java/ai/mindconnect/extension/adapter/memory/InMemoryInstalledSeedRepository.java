package ai.mindconnect.extension.adapter.memory;

import ai.mindconnect.extension.domain.InstalledSeed;
import ai.mindconnect.extension.port.out.InstalledSeedRepository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** {@link InstalledSeedRepository} in a map — for tests and embedders that keep nothing. */
public class InMemoryInstalledSeedRepository implements InstalledSeedRepository {

    private final Map<String, InstalledSeed> byKey = new ConcurrentHashMap<>();

    @Override
    public List<InstalledSeed> all() {
        return List.copyOf(byKey.values());
    }

    @Override
    public void record(Collection<InstalledSeed> seeds) {
        seeds.forEach(seed -> byKey.putIfAbsent(seed.key(), seed));
    }
}
