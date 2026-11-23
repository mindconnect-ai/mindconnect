package ai.mindconnect.agent.starter.runtime.bridge;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentDefinitionStatus;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;

/**
 * The earlier runtime's agent repository, kept in the agent management's
 * store: whoever still reads and writes agents the earlier way reads and
 * writes the agents the management holds, so there is one set of agents
 * while the callers move over one by one.
 *
 * <p>An agent written the earlier way is kept twice in one record: as the
 * definition the new runtime runs, and whole, as it was written, in that
 * definition's metadata. Read back the earlier way it is the whole one — as
 * long as the definition still says the same; once somebody changed the
 * agent through the management, what they changed is laid over it.
 *
 * <p>The agents the earlier store holds are taken over the first time a
 * namespace is looked into, under their ids.
 */
final class ManagedAgentDefinitionRepository implements AgentDefinitionRepository {

    private final Supplier<AgentRepository> agents;
    private final AgentDefinitionRepository earlier;
    private final EarlierAgentConverter converter;
    private final Supplier<NamespaceProvider> namespaces;
    private final Set<String> takenOver = ConcurrentHashMap.newKeySet();

    ManagedAgentDefinitionRepository(Supplier<AgentRepository> agents, AgentDefinitionRepository earlier,
                                     EarlierAgentConverter converter,
                                     Supplier<NamespaceProvider> namespaces) {
        this.agents = agents;
        this.earlier = earlier;
        this.converter = converter;
        this.namespaces = namespaces;
    }

    @Override
    public AgentDefinition save(AgentDefinition definition) {
        takeOver();
        ai.mindconnect.agent.management.api.AgentId id =
                ai.mindconnect.agent.management.api.AgentId.of(definition.id().value());
        ManagedAgent saved = agents.get().update(id, stored -> converter.managed(definition, stored.createdAt(),
                        stored.version() + 1))
                .orElseGet(() -> agents.get().create(converter.managed(definition, Instant.now(), 1)));
        return converter.earlier(saved);
    }

    @Override
    public Optional<AgentDefinition> findById(AgentId id) {
        takeOver();
        return agents.get().find(ai.mindconnect.agent.management.api.AgentId.of(id.value())).map(converter::earlier);
    }

    @Override
    public List<AgentDefinition> findAll() {
        takeOver();
        return agents.get().findAll().stream().map(converter::earlier).toList();
    }

    @Override
    public Optional<AgentDefinition> findByName(String name) {
        return findAll().stream().filter(agent -> agent.name().equals(name)).findFirst();
    }

    /** From both stores: an agent left in the earlier one would be taken over again. */
    @Override
    public void deleteById(AgentId id) {
        takeOver();
        agents.get().delete(ai.mindconnect.agent.management.api.AgentId.of(id.value()));
        earlier.deleteById(id);
    }

    /** Once per namespace: what the earlier store holds and the management does not, under the same id. */
    private void takeOver() {
        if (!takenOver.add(namespaces.get().current())) {
            return;
        }
        for (AgentDefinition kept : earlier.findAll()) {
            ai.mindconnect.agent.management.api.AgentId id =
                    ai.mindconnect.agent.management.api.AgentId.of(kept.id().value());
            if (agents.get().find(id).isEmpty()) {
                agents.get().create(converter.managed(kept,
                        kept.createdAt() == null ? Instant.now() : kept.createdAt(),
                        kept.version() == null ? 1 : kept.version()));
            }
        }
    }
}
