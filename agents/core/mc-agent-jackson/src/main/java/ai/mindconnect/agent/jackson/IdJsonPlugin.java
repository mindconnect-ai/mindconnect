package ai.mindconnect.agent.jackson;

import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;
import com.fasterxml.jackson.databind.Module;

/** Contributes {@link IdJsonModule}, so every mapper of the runtime writes an id as its value. */
public final class IdJsonPlugin implements Plugin {

    @Override
    public String name() {
        return "id-json";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.contribute(Module.class, new IdJsonModule());
    }
}
