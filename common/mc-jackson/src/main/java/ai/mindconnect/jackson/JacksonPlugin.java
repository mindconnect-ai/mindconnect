package ai.mindconnect.jackson;

import java.util.List;

import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Provides the {@link JsonMapperFactory} with every {@link Module} the plugins
 * contribute, and the host's shared {@link ObjectMapper}, built from it as the
 * host's {@link JsonMapperCustomizer} says.
 *
 * <p>Both are made when first asked for, after every plugin is installed: a
 * plugin contributes its modules in {@link Plugin#install}, and a module
 * contributed later than the first mapper is in no mapper.
 */
public final class JacksonPlugin implements Plugin {

    private final List<JsonMapperCustomizer> customizers;

    /** @param customizers applied to the shared mapper in order; none leaves Jackson's defaults */
    public JacksonPlugin(List<JsonMapperCustomizer> customizers) {
        this.customizers = List.copyOf(customizers);
    }

    @Override
    public String name() {
        return "jackson";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(JsonMapperFactory.class, () -> new JsonMapperFactory(registry.all(Module.class)));
        registry.provide(ObjectMapper.class, () -> {
            var mapper = registry.get(JsonMapperFactory.class).builder();
            for (JsonMapperCustomizer customizer : customizers) {
                mapper = customizer.customize(mapper);
            }
            return mapper.build();
        });
    }
}
