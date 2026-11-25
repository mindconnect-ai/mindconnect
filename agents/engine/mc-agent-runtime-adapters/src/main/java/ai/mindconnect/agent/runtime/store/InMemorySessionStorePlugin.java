package ai.mindconnect.agent.runtime.store;

import java.time.Clock;
import java.util.List;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.message.adapter.memory.InMemoryMessageRepository;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.plugin.Setting;

/** Sessions and what was said in them, kept as long as the process: for a library user who keeps nothing. */
public final class InMemorySessionStorePlugin implements Plugin {

    @Override
    public String name() {
        return "session-store-in-memory";
    }

    @Override
    public List<Setting<?>> settings() {
        return List.of(SessionMessages.SETTING);
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(SessionStore.class, () -> {
            ScopeSupplier scope = registry.get(ScopeSupplier.class);
            return switch (registry.setting(SessionMessages.SETTING)) {
                case OWN -> new DefaultSessionStore(new InMemorySessionRepository(scope),
                        new InMemorySessionMessageRepository(scope, Clock.systemUTC()));
                case CONVERSATION -> new RepositorySessionStore(new InMemorySessionRepository(scope),
                        new InMemoryMessageRepository(scope), registry.get(JsonMapperFactory.class));
            };
        });
    }
}
