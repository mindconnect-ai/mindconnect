package ai.mindconnect.agent.runtime.capabilities.prompt;

import ai.mindconnect.agent.runtime.api.port.out.TokenCounter;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/** The dependency-free answer to "how long is this": an estimate, until an exact counter is provided. */
public final class PromptPlugin implements Plugin {

    @Override
    public String name() {
        return "prompt";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(TokenCounter.class, EstimatingTokenCounter::new);
    }
}
