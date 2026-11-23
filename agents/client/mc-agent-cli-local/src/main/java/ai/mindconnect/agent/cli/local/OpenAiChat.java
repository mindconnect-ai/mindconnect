package ai.mindconnect.agent.cli.local;

import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.OkHttpClient;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmConfigRepository;
import ai.mindconnect.llm.adapter.openai.OpenAiResponsesGateway;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.llm.service.DefaultLlmGatewayRegistry;
import ai.mindconnect.llm.service.RoutingLlmChatService;

/**
 * An LLM gateway that knows one OpenAI model, set up from an API key. The
 * model is registered under its own name, so that is the name settings and
 * agents use for it.
 */
final class OpenAiChat {

    private static final int MAX_OUTPUT_TOKENS = 16_000;

    private final String apiKey;
    private final String model;

    OpenAiChat(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    LlmChat chat() {
        InMemoryLlmConfigRepository configs = new InMemoryLlmConfigRepository(ScopeSupplier.local());
        configs.save(new LlmConfig(LlmConfigId.random(), model, LlmProvider.OPENAI, model, null, apiKey, 0.0,
                MAX_OUTPUT_TOKENS, Map.of(), null, false, null, null, null, LlmConfigType.CHAT, null));
        // No read timeout: a reasoning model sends its first byte after it has thought.
        OkHttpClient http = new OkHttpClient.Builder()
                .readTimeout(Duration.ZERO).callTimeout(Duration.ofMinutes(10)).build();
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        return new RoutingLlmChatService(configs, new DefaultLlmGatewayRegistry(Map.of(
                LlmProvider.OPENAI, new OpenAiResponsesGateway(http, json, EncryptionHelper.noEncryption()))));
    }
}
