package ai.mindconnect.agent.management;

import java.time.Clock;
import java.util.List;

import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.SessionCleanup;
import ai.mindconnect.agent.management.api.SessionDirectoryPolicy;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.runtime.api.port.in.SessionEventListener;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.store.DefaultSessionStore;
import ai.mindconnect.agent.runtime.store.RepositorySessionStore;
import ai.mindconnect.agent.runtime.store.SessionMessageMigration;
import ai.mindconnect.agent.runtime.store.SessionMessageRepository;
import ai.mindconnect.agent.runtime.store.SessionMessages;
import ai.mindconnect.agent.runtime.store.SessionRepository;
import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.plugin.Setting;

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
    public List<Setting<?>> settings() {
        return List.of(SessionMessages.SETTING);
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(SessionStore.class, () -> sessionStore(registry));
        // From the conversations into the sessions' own repository; the host says when, and in which namespace.
        registry.provide(SessionMessageMigration.class, () -> new SessionMessageMigration(
                registry.get(SessionRepository.class), conversations(registry),
                registry.get(SessionMessageRepository.class), Clock.systemUTC()));
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

    private static SessionStore sessionStore(PluginRegistry registry) {
        return switch (registry.setting(SessionMessages.SETTING)) {
            case OWN -> new DefaultSessionStore(registry.get(SessionRepository.class),
                    registry.get(SessionMessageRepository.class));
            case CONVERSATION -> conversations(registry);
        };
    }

    /** The store that keeps a session's messages as a conversation of the message repository. */
    private static SessionStore conversations(PluginRegistry registry) {
        return new RepositorySessionStore(registry.get(SessionRepository.class),
                registry.get(MessageRepository.class), registry.get(JsonMapperFactory.class));
    }
}
