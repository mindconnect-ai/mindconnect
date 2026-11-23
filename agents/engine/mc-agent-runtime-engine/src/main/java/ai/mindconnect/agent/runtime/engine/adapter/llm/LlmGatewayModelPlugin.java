package ai.mindconnect.agent.runtime.engine.adapter.llm;

import java.util.Optional;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.runtime.api.port.out.MediaReader;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/** The model through the LLM gateway: every provider the gateway speaks to, behind the one model port. */
public final class LlmGatewayModelPlugin implements Plugin {

    private final LlmChat chat;
    private final LlmCallTraceListener traces;
    private final Function<String, Optional<LlmConfig>> configurations;

    /**
     * @param traces         who hears every provider call; {@link LlmCallTraceListener#NONE} for nobody
     * @param configurations what the LLM configuration says of a model by its name — its context window,
     *                       the kinds of media it reads; empty where the model is not known
     */
    public LlmGatewayModelPlugin(LlmChat chat, LlmCallTraceListener traces,
                                 Function<String, Optional<LlmConfig>> configurations) {
        this.chat = chat;
        this.traces = traces;
        this.configurations = configurations;
    }

    @Override
    public String name() {
        return "model-llm-gateway";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(ModelGateway.class, () -> new LlmChatModelGateway(chat, new ObjectMapper(), traces,
                configurations, registry.find(MediaReader.class).orElseGet(MediaReader::none)));
    }
}
