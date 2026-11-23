package ai.mindconnect.agent.management;

import java.time.Instant;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import ai.mindconnect.agent.management.api.AgentId;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;

/**
 * Agent management on a repository: the repository keeps, this class holds
 * what makes a set of agents usable — ids nobody chooses, names that are
 * unique, and a change that knows which version it was made from.
 */
public final class RepositoryAgentManagement implements AgentManagement {

    private final AgentRepository agents;

    public RepositoryAgentManagement(AgentRepository agents) {
        this.agents = agents;
    }

    @Override
    public List<ManagedAgent> list() {
        return agents.findAll().stream()
                .sorted(Comparator.comparing(agent -> agent.definition().name()))
                .toList();
    }

    @Override
    public Optional<ManagedAgent> find(AgentId id) {
        return agents.find(id);
    }

    @Override
    public Optional<ManagedAgent> findByName(String name) {
        return agents.findAll().stream().filter(agent -> agent.definition().name().equals(name)).findFirst();
    }

    @Override
    public ManagedAgent create(ManagedAgent agent) {
        AgentId id = AgentId.of(UUID.randomUUID().toString());
        requireFreeName(agent.definition().name(), id);
        Instant now = Instant.now();
        return agents.create(new ManagedAgent(id, agent.definition(), agent.group(), agent.icon(),
                agent.welcomeMessage(), agent.status(), now, now, 1));
    }

    @Override
    public ManagedAgent update(AgentId id, long expectedVersion, UnaryOperator<ManagedAgent> change) {
        return agents.update(id, stored -> {
            if (stored.version() != expectedVersion) {
                throw new ConcurrentModificationException("Agent " + stored.definition().name()
                        + " was changed since version " + expectedVersion);
            }
            ManagedAgent changed = change.apply(stored);
            requireFreeName(changed.definition().name(), id);
            // The identity, the time of creation and the count of changes are not the caller's to set.
            return new ManagedAgent(id, changed.definition(), changed.group(), changed.icon(),
                    changed.welcomeMessage(), changed.status(), stored.createdAt(), Instant.now(),
                    stored.version() + 1);
        }).orElseThrow(() -> new IllegalArgumentException("There is no agent " + id.value()));
    }

    @Override
    public ManagedAgent copy(AgentId id) {
        ManagedAgent original = agents.find(id).orElseThrow(() ->
                new IllegalArgumentException("There is no agent " + id.value()));
        AgentDefinition definition = original.definition();
        return create(original.withDefinition(new AgentDefinition(freeNameLike(definition.name()),
                definition.description(), definition.instructions(), definition.modelSettings(),
                definition.tools(), definition.maxRounds(), definition.metadata())));
    }

    @Override
    public void delete(AgentId id) {
        agents.delete(id);
    }

    private void requireFreeName(String name, AgentId owner) {
        findByName(name).filter(taken -> !taken.id().equals(owner)).ifPresent(taken -> {
            throw new IllegalArgumentException("There is an agent named " + name + " already");
        });
    }

    /** {@code name-copy}, or with the first number that makes it free. */
    private String freeNameLike(String name) {
        String candidate = name + "-copy";
        for (int number = 2; findByName(candidate).isPresent(); number++) {
            candidate = name + "-copy-" + number;
        }
        return candidate;
    }
}
