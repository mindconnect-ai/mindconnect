package ai.mindconnect.jackson;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

class JacksonPluginTest {

    private static final ContributedModule.Word HELLO = new ContributedModule.Word("hello");

    /** Contributes its module the way any plugin does: when it is installed. */
    private static final Plugin CONTRIBUTING = new Plugin() {
        @Override
        public String name() {
            return "contributing";
        }

        @Override
        public void install(PluginRegistry registry) {
            registry.contribute(Module.class, new ContributedModule());
        }
    };

    @Test
    void the_factory_and_the_shared_mapper_have_every_contributed_module() throws Exception {
        PluginRegistry registry = new DefaultPluginRegistry();
        registry.install(new JacksonPlugin(List.of()));
        registry.install(CONTRIBUTING);
        registry.start();

        assertThat(registry.get(JsonMapperFactory.class).create().writeValueAsString(HELLO)).isEqualTo("\"hello\"");
        assertThat(registry.get(ObjectMapper.class).writeValueAsString(HELLO)).isEqualTo("\"hello\"");
    }

    @Test
    void the_shared_mapper_is_built_as_the_host_says() throws Exception {
        PluginRegistry registry = new DefaultPluginRegistry();
        registry.install(new JacksonPlugin(List.of(mapper -> mapper.enable(SerializationFeature.INDENT_OUTPUT))));
        registry.install(CONTRIBUTING);
        registry.start();

        assertThat(registry.get(ObjectMapper.class).writeValueAsString(Map.of("a", HELLO)))
                .isEqualTo("{\n  \"a\" : \"hello\"\n}");
        assertThat(registry.get(JsonMapperFactory.class).create().isEnabled(SerializationFeature.INDENT_OUTPUT))
                .as("the factory's mappers are not the host's").isFalse();
    }
}
