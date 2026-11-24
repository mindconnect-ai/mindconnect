package ai.mindconnect.agent.runtime.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ai.mindconnect.agent.tool.OverlayToolRegistry;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.tool.ToolRepository;
import ai.mindconnect.agent.tool.ToolSettings;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.PluginRegistry;

class ToolRegistryPluginTest {

    /**
     * The tool catalog unwraps the registry to list the tools an operator
     * switched off. A layer on top of the settings hid them: the row was
     * gone, and with it the only way to switch the tool on again.
     */
    @Test
    void the_operators_settings_stay_outermost_when_a_plugin_layers_the_registry() {
        PluginRegistry registry = new DefaultPluginRegistry();
        ToolRegistry[] beneathTheLayer = new ToolRegistry[1];
        registry.provide(ToolRepository.class, InMemorySettings::new);
        registry.install(new ToolRegistryPlugin(Map.of(), "", type -> Optional.empty()));
        registry.contribute(ToolRegistryPlugin.Layer.class, tools -> {
            beneathTheLayer[0] = tools;
            return tools;
        });
        registry.start();

        ToolRegistry effective = registry.get(ToolRegistry.class);

        assertThat(effective).isInstanceOf(OverlayToolRegistry.class);
        assertThat(((OverlayToolRegistry) effective).source()).isSameAs(beneathTheLayer[0]);
    }

    private static final class InMemorySettings implements ToolRepository {

        private final Map<String, ToolSettings> settings = new HashMap<>();

        @Override
        public ToolSettings settings(String toolName) {
            return settings.getOrDefault(toolName, ToolSettings.none());
        }

        @Override
        public Map<String, ToolSettings> all() {
            return Map.copyOf(settings);
        }

        @Override
        public void save(String toolName, ToolSettings toolSettings) {
            settings.put(toolName, toolSettings);
        }

        @Override
        public void delete(String toolName) {
            settings.remove(toolName);
        }

        @Override
        public long version() {
            return 0;
        }
    }
}
