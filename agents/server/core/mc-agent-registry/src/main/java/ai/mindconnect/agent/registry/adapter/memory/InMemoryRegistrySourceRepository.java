package ai.mindconnect.agent.registry.adapter.memory;

import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.agent.registry.domain.RegistrySourceId;
import ai.mindconnect.agent.registry.port.out.RegistrySourceRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The registries of a process that keeps nothing — tests, and an embedded runtime. */
public class InMemoryRegistrySourceRepository implements RegistrySourceRepository {

    private final Map<RegistrySourceId, RegistrySource> sources = new ConcurrentHashMap<>();

    @Override
    public RegistrySource save(RegistrySource source) {
        sources.put(source.id(), source);
        return source;
    }

    @Override
    public Optional<RegistrySource> findById(RegistrySourceId id) {
        return Optional.ofNullable(sources.get(id));
    }

    @Override
    public List<RegistrySource> findAll() {
        return sources.values().stream()
                .sorted(Comparator.comparing(RegistrySource::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public void deleteById(RegistrySourceId id) {
        sources.remove(id);
    }
}
