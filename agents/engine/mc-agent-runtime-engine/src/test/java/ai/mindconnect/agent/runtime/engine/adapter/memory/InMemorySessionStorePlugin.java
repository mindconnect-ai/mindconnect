package ai.mindconnect.agent.runtime.engine.adapter.memory;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.engine.adapter.store.InMemorySessionRepository;
import ai.mindconnect.agent.runtime.engine.adapter.store.RepositorySessionStore;
import ai.mindconnect.message.adapter.memory.InMemoryMessageRepository;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/** Sessions that live as long as the test. */
public final class InMemorySessionStorePlugin implements Plugin {

    @Override
    public String name() {
        return "session-store-in-memory";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(SessionStore.class,
                () -> {
                    ScopeSupplier namespaces = registry.get(ScopeSupplier.class);
                    return new RepositorySessionStore(new InMemorySessionRepository(namespaces),
                            new InMemoryMessageRepository(namespaces));
                });
    }
}
