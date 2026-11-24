package ai.mindconnect.agent.management;

import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.SessionCleanup;
import ai.mindconnect.agent.management.api.SessionDirectoryPolicy;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.store.RepositorySessionStore;
import ai.mindconnect.agent.runtime.store.SessionRepository;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * The agents and sessions of an installation, kept in the repositories the
 * runtime is given: the management API over them, the runtime's session
 * store on the same sessions, and the stored agents as the agents another
 * agent finds by name.
 */
public final class ManagementPlugin implements Plugin {

    @Override
    public String name() {
        return "management";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(SessionStore.class, () -> new RepositorySessionStore(
                registry.get(SessionRepository.class), registry.get(MessageRepository.class)));
        registry.provide(AgentManagement.class,
                () -> new RepositoryAgentManagement(registry.get(AgentRepository.class)));
        registry.provide(SessionManagement.class, () -> new RepositorySessionManagement(
                registry.get(SessionRepository.class), registry.get(MessageRepository.class),
                registry.get(AgentRepository.class),
                registry.find(SessionDirectoryPolicy.class).orElseGet(SessionDirectoryPolicy::unrestricted),
                registry.all(SessionCleanup.class)));
        registry.contribute(AgentResolver.class, (agentName, session) ->
                new ManagedAgentResolver(registry.get(AgentManagement.class)).resolve(agentName, session));
    }
}
