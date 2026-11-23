package ai.mindconnect.agent.runtime.engine.adapter.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/** The model through the LLM gateway: every provider the gateway speaks to, behind the one model port. */
public final class LlmGatewayModelPlugin implements Plugin {

    private final LlmChat chat;

    public LlmGatewayModelPlugin(LlmChat chat) {
        this.chat = chat;
    }

    @Override
    public String name() {
        return "model-llm-gateway";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(ModelGateway.class, () -> new LlmChatModelGateway(chat, new ObjectMapper()));
    }
}
