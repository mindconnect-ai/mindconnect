package ai.mindconnect.agent.management;

import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.SessionCleanup;
import ai.mindconnect.agent.management.api.SessionDirectoryPolicy;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.runtime.api.port.in.SessionEventListener;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.store.RepositorySessionStore;
import ai.mindconnect.agent.runtime.store.SessionRepository;
import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * The agents and sessions of an installation, kept in the repositories the
 * runtime is given: the management API over them, the runtime's session
 * store on the same sessions, the stored agents as the agents another
 * agent finds by name, and what a user is told about their sessions.
 */
public final class ManagementPlugin implements Plugin {

    @Override
    public String name() {
        return "management";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(SessionStore.class, () -> sessionStore(registry));
        registry.provide(AgentManagement.class,
                () -> new RepositoryAgentManagement(registry.get(AgentRepository.class)));
        // Made on first use: the turn service is built once every plugin is installed.
        registry.provide(SessionLifecycle.class, () -> new SessionLifecycle(
                registry.get(SessionRepository.class),
                session -> registry.get(TurnService.class).runningTurn(session)));
        registry.provide(SessionManagement.class, () -> new RepositorySessionManagement(
                registry.get(SessionRepository.class), registry.get(SessionStore.class),
                registry.get(AgentRepository.class),
                registry.find(SessionDirectoryPolicy.class).orElseGet(SessionDirectoryPolicy::unrestricted),
                registry.all(SessionCleanup.class), registry.get(SessionLifecycle.class)));
        // Hears every session, of whatever namespace: what a user is told is about their sessions everywhere.
        registry.contribute(SessionEventListener.class,
                event -> registry.get(SessionLifecycle.class).onEvent(event));
        registry.contribute(AgentResolver.class, (agentName, session) ->
                new ManagedAgentResolver(registry.get(AgentManagement.class)).resolve(agentName, session));
    }

    private static RepositorySessionStore sessionStore(PluginRegistry registry) {
        return new RepositorySessionStore(registry.get(SessionRepository.class),
                registry.get(MessageRepository.class), registry.get(JsonMapperFactory.class));
    }
}
