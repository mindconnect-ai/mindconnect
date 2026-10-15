package ai.mindconnect.extension.adapter.memory;

import ai.mindconnect.extension.domain.ExtensionActivation;
import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.extension.port.out.ExtensionActivationRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** {@link ExtensionActivationRepository} in a map — for tests and embedders that keep nothing. */
public class InMemoryExtensionActivationRepository implements ExtensionActivationRepository {

    private final Map<ExtensionId, ExtensionActivation> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<ExtensionActivation> find(ExtensionId id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<ExtensionActivation> all() {
        return byId.values().stream()
                .sorted(Comparator.comparing(activation -> activation.extensionId().value()))
                .toList();
    }

    @Override
    public void save(ExtensionActivation activation) {
        byId.put(activation.extensionId(), activation);
    }

    @Override
    public void delete(ExtensionId id) {
        byId.remove(id);
    }
}
