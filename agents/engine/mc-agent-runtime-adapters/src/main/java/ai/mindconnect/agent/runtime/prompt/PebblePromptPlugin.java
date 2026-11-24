package ai.mindconnect.agent.runtime.prompt;

import ai.mindconnect.agent.runtime.api.port.out.PromptRenderer;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/** Instructions are Pebble templates. Leave it out and provide another {@link PromptRenderer} for another language. */
public final class PebblePromptPlugin implements Plugin {

    @Override
    public String name() {
        return "prompt-pebble";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(PromptRenderer.class, PebblePromptRenderer::new);
    }
}
