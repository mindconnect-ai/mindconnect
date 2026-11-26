package ai.mindconnect.agent.jackson;

import ai.mindconnect.llm.jackson.LlmJsonModule;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;
import com.fasterxml.jackson.databind.Module;

/** Contributes {@link LlmJsonModule}, so every mapper of the runtime stores LLM configs and prices in their form. */
public final class LlmJsonPlugin implements Plugin {

    @Override
    public String name() {
        return "llm-json";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.contribute(Module.class, new LlmJsonModule());
    }
}
