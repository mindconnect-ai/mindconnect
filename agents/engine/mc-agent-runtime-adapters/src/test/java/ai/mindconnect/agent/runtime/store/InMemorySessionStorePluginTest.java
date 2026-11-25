package ai.mindconnect.agent.runtime.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.plugin.SettingSource;

/** The setting {@code sessions.messages} chooses where the plugin's store keeps a session's messages. */
class InMemorySessionStorePluginTest {

    private static SessionStore storeWith(Map<String, String> settings) {
        PluginRegistry registry = new DefaultPluginRegistry();
        registry.provide(SettingSource.class, () -> SettingSource.of(settings));
        registry.provide(ScopeSupplier.class, () -> ScopeSupplier.fixed(new Namespace("test")));
        registry.provide(JsonMapperFactory.class, () -> new JsonMapperFactory(List.of()));
        registry.install(new InMemorySessionStorePlugin());
        return registry.get(SessionStore.class);
    }

    @Test
    void without_the_setting_the_messages_are_kept_in_their_own_repository() {
        assertThat(storeWith(Map.of())).isInstanceOf(DefaultSessionStore.class);
    }

    @Test
    void own_keeps_them_in_a_repository_of_their_own() {
        assertThat(storeWith(Map.of("sessions.messages", "own"))).isInstanceOf(DefaultSessionStore.class);
        assertThat(storeWith(Map.of("sessions.messages", "conversation"))).isInstanceOf(RepositorySessionStore.class);
    }

    @Test
    void any_other_value_is_refused() {
        assertThatThrownBy(() -> storeWith(Map.of("sessions.messages", "files")))
                .hasStackTraceContaining("neither conversation nor own");
    }
}
