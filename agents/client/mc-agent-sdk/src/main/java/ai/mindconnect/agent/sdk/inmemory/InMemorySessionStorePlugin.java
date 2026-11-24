package ai.mindconnect.agent.sdk.inmemory;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.store.InMemorySessionRepository;
import ai.mindconnect.agent.runtime.store.RepositorySessionStore;
import ai.mindconnect.message.adapter.memory.InMemoryMessageRepository;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/** Sessions and what was said in them, kept as long as the process: for a library user who keeps nothing. */
public final class InMemorySessionStorePlugin implements Plugin {

    @Override
    public String name() {
        return "session-store-in-memory";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(SessionStore.class, () -> {
            ScopeSupplier scope = registry.get(ScopeSupplier.class);
            return new RepositorySessionStore(new InMemorySessionRepository(scope),
                    new InMemoryMessageRepository(scope));
        });
    }
}
