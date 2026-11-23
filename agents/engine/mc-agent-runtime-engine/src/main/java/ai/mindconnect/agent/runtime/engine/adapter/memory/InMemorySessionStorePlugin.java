package ai.mindconnect.agent.runtime.engine.adapter.memory;

import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.engine.adapter.store.RepositorySessionStore;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/** Sessions that live as long as the process: for a library user who keeps nothing, and for tests. */
public final class InMemorySessionStorePlugin implements Plugin {

    @Override
    public String name() {
        return "session-store-in-memory";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(SessionStore.class,
                () -> RepositorySessionStore.inMemory(registry.get(NamespaceProvider.class)));
    }
}
