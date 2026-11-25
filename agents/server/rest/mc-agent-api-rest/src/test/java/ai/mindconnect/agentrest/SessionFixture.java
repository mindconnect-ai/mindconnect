package ai.mindconnect.agentrest;

import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.agent.jackson.IdJsonModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.management.RepositorySessionManagement;
import ai.mindconnect.agent.management.adapter.InMemoryAgentRepository;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionDirectoryPolicy;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.store.InMemorySessionRepository;
import ai.mindconnect.agent.runtime.store.RepositorySessionStore;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.message.adapter.memory.InMemoryMessageRepository;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.PluginRegistry;

/** A runtime that knows sessions and nothing else: what a test of session access needs. */
public final class SessionFixture {

    private static final AgentDefinition HELPER = new AgentDefinition("helper", "Helps.", "You help.",
            ModelSettings.NONE, List.of(), 5, Map.of());

    private final RepositorySessionStore store;
    private final SessionManagement management;
    private final AgentRuntime runtime;

    public SessionFixture() {
        var sessions = new InMemorySessionRepository(ScopeSupplier.fixed(new Namespace("test")));
        var messages = new InMemoryMessageRepository(ScopeSupplier.fixed(new Namespace("test")));
        store = new RepositorySessionStore(sessions, messages, new JsonMapperFactory(List.of(new IdJsonModule())));
        // A spy, so a test can look at what reached the management.
        management = org.mockito.Mockito.spy(new RepositorySessionManagement(sessions, store,
                new InMemoryAgentRepository(ScopeSupplier.fixed(new Namespace("test"))), SessionDirectoryPolicy.unrestricted(),
                List.of(),
                new ai.mindconnect.agent.management.SessionLifecycle(sessions, session -> java.util.Optional.empty())));
        PluginRegistry registry = new DefaultPluginRegistry();
        registry.provide(SessionManagement.class, () -> management);
        // Enough of the runtime's own session service to find a session by its id.
        SessionService found = org.mockito.Mockito.mock(SessionService.class);
        org.mockito.Mockito.when(found.find(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> store.find(call.getArgument(0)));
        registry.provide(SessionService.class, () -> found);
        registry.start();
        runtime = new AgentRuntime(registry, () -> { });
    }

    public AgentRuntime runtime() {
        return runtime;
    }

    public SessionManagement management() {
        return management;
    }

    /** A session of {@code user}, working in {@code workingDir} when one is given. */
    public ManagedSession open(String user, String workingDir) {
        return open(user, workingDir == null ? Map.of() : Map.of(ManagedSession.WORKING_DIRECTORY, workingDir));
    }

    /** A session of {@code user} on the stored agent of that id. */
    public ManagedSession openOn(String agentId, String user) {
        return open(user, Map.of(ManagedSession.AGENT_ID, agentId));
    }

    private ManagedSession open(String user, Map<String, Object> metadata) {
        SessionId id = SessionId.of(UUID.randomUUID().toString());
        store.create(new Session(id, UserId.of(user), null, HELPER, metadata,
                Instant.parse("2026-11-01T00:00:00Z")));
        ManagedSession opened = management.find(id).orElseThrow();
        org.mockito.Mockito.clearInvocations(management);
        return opened;
    }
}
