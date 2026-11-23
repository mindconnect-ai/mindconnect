package ai.mindconnect.agent.starter.runtime.bridge;

import java.util.Optional;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.common.DomainException;
import java.util.concurrent.atomic.AtomicReference;
import ai.mindconnect.agent.runtime.service.SessionAgentResolver;
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
    private final EarlierAgentConverter converter;
    private final ObjectMapper json;

    ManagedStoresFeature(Supplier<AgentRepository> agents, Supplier<SessionRepository> sessions,
                         Supplier<NamespaceProvider> namespaces, EarlierAgentConverter converter,
                         ObjectMapper json) {
        this.agents = agents;
        this.sessions = sessions;
        this.namespaces = namespaces;
        this.converter = converter;
        this.json = json;
    }

    /** The definition the chat's choice amounts to right now; empty when the agent behind it is gone. */
    static Optional<AgentDefinition> agentOf(AgentSession written, AgentDefinitionRepository definitions,
                                             EarlierAgentConverter converter) {
        try {
            return Optional.of(converter.runs(new SessionAgentResolver(definitions).resolve(written)));
        } catch (DomainException gone) {
            return Optional.empty();
        }
    }

    @Override
    public String name() {
        return "managed-stores";
    }

    @Override
    public void configure(FeatureContext ctx) {
        // The agents as they are kept now, for a chat whose choice amounts to a definition of its own.
        AtomicReference<AgentDefinitionRepository> definitions = new AtomicReference<>();
        ctx.decorate(AgentDefinitionRepository.class, earlier -> {
            definitions.set(new ManagedAgentDefinitionRepository(agents, earlier, converter, namespaces));
            return definitions.get();
        });
        ctx.decorate(AgentSessionRepository.class, earlier ->
                new ManagedAgentSessionRepository(sessions, earlier, json, namespaces,
                        written -> agentOf(written, definitions.get(), converter)));
    }
}
