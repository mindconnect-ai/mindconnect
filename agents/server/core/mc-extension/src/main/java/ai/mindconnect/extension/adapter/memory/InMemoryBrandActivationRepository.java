package ai.mindconnect.extension.adapter.memory;

import ai.mindconnect.extension.domain.BrandActivation;
import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.extension.port.out.BrandActivationRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** {@link BrandActivationRepository} in a map — for tests and embedders that keep nothing. */
public class InMemoryBrandActivationRepository implements BrandActivationRepository {

    private record Key(String brand, ExtensionId id) {
    }

    private final Map<Key, BrandActivation> byKey = new ConcurrentHashMap<>();

    @Override
    public Optional<BrandActivation> find(String brand, ExtensionId id) {
        return Optional.ofNullable(byKey.get(new Key(brand, id)));
    }

    @Override
    public List<BrandActivation> all(String brand) {
        return byKey.values().stream()
                .filter(a -> a.brand().equals(brand))
                .sorted(Comparator.comparing(a -> a.extensionId().value()))
                .toList();
    }

    @Override
    public void save(BrandActivation activation) {
        byKey.put(new Key(activation.brand(), activation.extensionId()), activation);
    }

    @Override
    public void delete(String brand, ExtensionId id) {
        byKey.remove(new Key(brand, id));
    }
}
