package ai.mindconnect.credentials.adapter.memory;

import ai.mindconnect.credentials.domain.OAuthProvider;
import ai.mindconnect.credentials.port.out.OAuthProviderRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** {@link OAuthProviderRepository} in a map — for tests and hosts that keep nothing. */
public class InMemoryOAuthProviderRepository implements OAuthProviderRepository {

    private final Map<UUID, OAuthProvider> byId = new ConcurrentHashMap<>();

    @Override
    public void save(OAuthProvider provider) {
        byId.put(provider.id(), provider);
    }

    @Override
    public Optional<OAuthProvider> findById(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<OAuthProvider> findByName(String name) {
        return byId.values().stream().filter(provider -> provider.name().equals(name)).findFirst();
    }

    @Override
    public List<OAuthProvider> findAll() {
        return byId.values().stream().sorted(Comparator.comparing(OAuthProvider::name)).toList();
    }

    @Override
    public void deleteById(UUID id) {
        byId.remove(id);
    }
}
