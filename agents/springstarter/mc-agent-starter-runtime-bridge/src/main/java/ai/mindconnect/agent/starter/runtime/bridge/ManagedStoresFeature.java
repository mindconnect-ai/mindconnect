package ai.mindconnect.agent.starter.runtime.bridge;

import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.engine.port.out.SessionRepository;
import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.RuntimeFeature;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;

/**
 * Puts the earlier runtime's agents and sessions into the stores of the new
 * one. Installed into the earlier runtime as one of its features: wherever
 * that runtime, its tools or the application ask for an agent or a session
 * repository, they get one that keeps its records where the management and
 * the runtime on the plugin kernel keep theirs.
 *
 * <p>The stores are asked for when first needed, not when the feature is
 * configured: the earlier runtime is built before they are.
 */
final class ManagedStoresFeature implements RuntimeFeature {

    private final Supplier<AgentRepository> agents;
    private final Supplier<SessionRepository> sessions;
    private final Supplier<NamespaceProvider> namespaces;
    private final AgentDefinitionMapper mapper;
    private final ObjectMapper json;

    ManagedStoresFeature(Supplier<AgentRepository> agents, Supplier<SessionRepository> sessions,
                         Supplier<NamespaceProvider> namespaces, AgentDefinitionMapper mapper, ObjectMapper json) {
        this.agents = agents;
        this.sessions = sessions;
        this.namespaces = namespaces;
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public String name() {
        return "managed-stores";
    }

    @Override
    public void configure(FeatureContext ctx) {
        ctx.decorate(AgentDefinitionRepository.class, earlier ->
                new ManagedAgentDefinitionRepository(agents, earlier, mapper, json, namespaces));
        ctx.decorate(AgentSessionRepository.class, earlier ->
                new ManagedAgentSessionRepository(sessions, earlier, json, namespaces));
    }
}
