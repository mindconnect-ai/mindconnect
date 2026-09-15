package ai.mindconnect.namespace.adapter.memory;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.port.out.NamespaceRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** {@link NamespaceRepository} in a map — for tests and embedders that keep nothing. */
public class InMemoryNamespaceRepository implements NamespaceRepository {

    private final Map<Namespace, NamespaceDefinition> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<NamespaceDefinition> findById(Namespace id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<NamespaceDefinition> findAll() {
        return byId.values().stream().sorted(Comparator.comparing(ns -> ns.id().value())).toList();
    }

    @Override
    public List<NamespaceDefinition> findByMember(UserId user) {
        return findAll().stream().filter(ns -> ns.isMember(user)).toList();
    }

    @Override
    public void save(NamespaceDefinition namespace) {
        byId.put(namespace.id(), namespace);
    }

    @Override
    public boolean deleteById(Namespace id) {
        return byId.remove(id) != null;
    }
}
