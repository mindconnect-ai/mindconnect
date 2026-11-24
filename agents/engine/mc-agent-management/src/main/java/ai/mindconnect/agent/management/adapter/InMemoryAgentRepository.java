package ai.mindconnect.agent.management.adapter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.ScopeSupplier;

/** Agents that live as long as the process: for a program that defines its agents in code, and for tests. */
public final class InMemoryAgentRepository implements AgentRepository {

    /** An agent belongs to a namespace; the same id in another is another agent. */
    private record Key(String namespace, AgentId id) { }

    private final Map<Key, ManagedAgent> agents = new ConcurrentHashMap<>();
    private final ScopeSupplier scope;

    public InMemoryAgentRepository(ScopeSupplier scope) {
        this.scope = scope;
    }

    private Key key(AgentId id) {
        return new Key(scope.namespace().value(), id);
    }

    @Override
    public ManagedAgent create(ManagedAgent agent) {
        if (agents.putIfAbsent(key(agent.id()), agent) != null) {
            throw new IllegalStateException("Agent " + agent.id().value() + " exists already");
        }
        return agent;
    }

    @Override
    public Optional<ManagedAgent> find(AgentId id) {
        return Optional.ofNullable(agents.get(key(id)));
    }

    @Override
    public List<ManagedAgent> findAll() {
        String namespace = scope.namespace().value();
        return agents.entrySet().stream()
                .filter(entry -> entry.getKey().namespace().equals(namespace))
                .map(Map.Entry::getValue)
                .toList();
    }

    @Override
    public Optional<ManagedAgent> update(AgentId id, UnaryOperator<ManagedAgent> change) {
        return Optional.ofNullable(agents.computeIfPresent(key(id), (key, agent) -> change.apply(agent)));
    }

    @Override
    public boolean delete(AgentId id) {
        return agents.remove(key(id)) != null;
    }
}
