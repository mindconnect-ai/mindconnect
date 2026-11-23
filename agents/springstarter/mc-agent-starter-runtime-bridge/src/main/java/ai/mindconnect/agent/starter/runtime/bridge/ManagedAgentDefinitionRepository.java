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

    /** Metadata of a definition: the agent as the earlier runtime wrote it, whole. */
    static final String EARLIER = "earlier.definition";

    private final Supplier<AgentRepository> agents;
    private final AgentDefinitionRepository earlier;
    private final AgentDefinitionMapper mapper;
    private final ObjectMapper json;
    private final Supplier<NamespaceProvider> namespaces;
    private final Set<String> takenOver = ConcurrentHashMap.newKeySet();

    ManagedAgentDefinitionRepository(Supplier<AgentRepository> agents, AgentDefinitionRepository earlier,
                                     AgentDefinitionMapper mapper, ObjectMapper json,
                                     Supplier<NamespaceProvider> namespaces) {
        this.agents = agents;
        this.earlier = earlier;
        this.mapper = mapper;
        this.json = json;
        this.namespaces = namespaces;
    }

    @Override
    public AgentDefinition save(AgentDefinition definition) {
        takeOver();
        ai.mindconnect.agent.management.api.AgentId id =
                ai.mindconnect.agent.management.api.AgentId.of(definition.id().value());
        ManagedAgent saved = agents.get().update(id, stored -> managed(definition, stored.createdAt(),
                        stored.version() + 1))
                .orElseGet(() -> agents.get().create(managed(definition, Instant.now(), 1)));
        return earlier(saved);
    }

    @Override
    public Optional<AgentDefinition> findById(AgentId id) {
        takeOver();
        return agents.get().find(ai.mindconnect.agent.management.api.AgentId.of(id.value())).map(this::earlier);
    }

    @Override
    public List<AgentDefinition> findAll() {
        takeOver();
        return agents.get().findAll().stream().map(this::earlier).toList();
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
                agents.get().create(managed(kept,
                        kept.createdAt() == null ? Instant.now() : kept.createdAt(),
                        kept.version() == null ? 1 : kept.version()));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private ManagedAgent managed(AgentDefinition definition, Instant createdAt, long version) {
        ai.mindconnect.agent.runtime.api.domain.AgentDefinition runs = mapper.toRuntime(definition);
        Map<String, Object> metadata = new HashMap<>(runs.metadata());
        metadata.put(EARLIER, json.convertValue(definition, Map.class));
        return new ManagedAgent(
                ai.mindconnect.agent.management.api.AgentId.of(definition.id().value()),
                new ai.mindconnect.agent.runtime.api.domain.AgentDefinition(runs.name(), runs.description(),
                        runs.instructions(), runs.modelSettings(), runs.tools(), runs.maxRounds(), metadata),
                definition.group(), definition.icon(), definition.welcomeMessage(),
                switch (definition.status()) {
                    case DRAFT -> ManagedAgent.Status.DRAFT;
                    case ACTIVE -> ManagedAgent.Status.ACTIVE;
                    case DEPRECATED -> ManagedAgent.Status.DEPRECATED;
                },
                createdAt, Instant.now(), version);
    }

    /** The agent as the earlier runtime knows it, with what the management changed since laid over it. */
    private AgentDefinition earlier(ManagedAgent agent) {
        ai.mindconnect.agent.runtime.api.domain.AgentDefinition runs = agent.definition();
        AgentDefinition written = runs.metadata().get(EARLIER) instanceof Map<?, ?> whole
                ? json.convertValue(whole, AgentDefinition.class) : null;
        AgentDefinitionStatus status = switch (agent.status()) {
            case DRAFT -> AgentDefinitionStatus.DRAFT;
            case ACTIVE -> AgentDefinitionStatus.ACTIVE;
            case DEPRECATED -> AgentDefinitionStatus.DEPRECATED;
        };
        // Still what was written, as far as the runtime's definition can tell: everything of it holds,
        // also what the definition has no place for — a tool switched off, a memory setting in full.
        boolean untouched = written != null && sameAsRuns(mapper.toRuntime(written), runs);
        return new AgentDefinition(
                AgentId.of(agent.id().value()),
                runs.name(),
                runs.description(),
                agent.group(),
                agent.icon(),
                runs.instructions(),
                agent.welcomeMessage(),
                runs.modelSettings().model(),
                runs.maxRounds(),
                written == null ? null : written.memoryConfig(),
                status,
                untouched ? written.tools()
                        : runs.tools().stream().map(RegistryToolResolver::agentTool).toList(),
                written == null ? List.of() : written.responseReviewers(),
                runs.metadata().get(SubAgentPlugin.SUB_AGENTS) instanceof List<?> named
                        ? named.stream().map(String::valueOf).toList() : List.of(),
                written == null ? null : written.callableByAgents(),
                written == null ? null : written.skills(),
                agent.createdAt(), agent.updatedAt(), agent.version());
    }

    /** Whether two definitions run the same, leaving aside the copy of the earlier one that one of them carries. */
    private static boolean sameAsRuns(ai.mindconnect.agent.runtime.api.domain.AgentDefinition one,
                                      ai.mindconnect.agent.runtime.api.domain.AgentDefinition other) {
        Map<String, Object> metadata = new HashMap<>(other.metadata());
        metadata.remove(EARLIER);
        return one.equals(new ai.mindconnect.agent.runtime.api.domain.AgentDefinition(other.name(),
                other.description(), other.instructions(), other.modelSettings(), other.tools(),
                other.maxRounds(), metadata));
    }
}
