package ai.mindconnect.agent.starter.runtime.bridge;

import ai.mindconnect.agent.runtime.capabilities.review.ResponseReviewPlugin;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentDefinitionStatus;

/**
 * An agent of the earlier runtime as the definition the new one runs, and
 * back. The definition carries the earlier agent whole in its metadata, so
 * what it has no place for — a tool switched off, a memory setting in full —
 * is still there for whoever reads the earlier way.
 */
final class EarlierAgentConverter {

    /** Metadata of a definition: the agent as the earlier runtime wrote it, whole. */
    static final String EARLIER = "earlier.definition";

    private final AgentDefinitionMapper mapper;
    private final ObjectMapper json;

    EarlierAgentConverter(AgentDefinitionMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    /** The definition the new runtime runs, with the earlier agent kept in it. */
    @SuppressWarnings("unchecked")
    ai.mindconnect.agent.runtime.api.domain.AgentDefinition runs(AgentDefinition definition) {
        ai.mindconnect.agent.runtime.api.domain.AgentDefinition runs = mapper.toRuntime(definition);
        Map<String, Object> metadata = new HashMap<>(runs.metadata());
        metadata.put(EARLIER, json.convertValue(definition, Map.class));
        return new ai.mindconnect.agent.runtime.api.domain.AgentDefinition(runs.name(), runs.description(),
                runs.instructions(), runs.modelSettings(), runs.tools(), runs.maxRounds(), metadata);
    }

    ManagedAgent managed(AgentDefinition definition, Instant createdAt, long version) {
        return new ManagedAgent(
                ai.mindconnect.agent.management.api.AgentId.of(definition.id().value()),
                runs(definition),
                definition.group(), definition.icon(), definition.welcomeMessage(),
                switch (definition.status()) {
                    case DRAFT -> ManagedAgent.Status.DRAFT;
                    case ACTIVE -> ManagedAgent.Status.ACTIVE;
                    case DEPRECATED -> ManagedAgent.Status.DEPRECATED;
                },
                createdAt, Instant.now(), version);
    }

    /** The earlier agent a definition carries; {@code null} for one that never was written the earlier way. */
    AgentDefinition written(ai.mindconnect.agent.runtime.api.domain.AgentDefinition runs) {
        return runs.metadata().get(EARLIER) instanceof Map<?, ?> whole
                ? json.convertValue(whole, AgentDefinition.class) : null;
    }

    /** The agent as the earlier runtime knows it, with what the management changed since laid over it. */
    AgentDefinition earlier(ManagedAgent agent) {
        ai.mindconnect.agent.runtime.api.domain.AgentDefinition runs = agent.definition();
        AgentDefinition written = written(runs);
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
                runs.metadata().get(ResponseReviewPlugin.REVIEWERS) instanceof List<?> reviewers
                        ? reviewers.stream().map(String::valueOf).toList() : List.of(),
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
