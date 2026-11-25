package ai.mindconnect.agent.runtime.infrastructure;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.runtime.api.port.out.MediaReader;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.agent.runtime.llm.LlmCallTraceListener;
import ai.mindconnect.agent.runtime.llm.LlmChatModelGateway;
import ai.mindconnect.agent.trace.port.out.LlmCallTraceRepository;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.openai.AzureOpenAiGateway;
import ai.mindconnect.llm.adapter.anthropic.ClaudeGateway;
import ai.mindconnect.llm.adapter.gemini.GeminiGateway;
import ai.mindconnect.llm.adapter.openai.OpenAiCompatibleGateway;
import ai.mindconnect.llm.adapter.openai.OpenAiEmbeddingsGateway;
import ai.mindconnect.llm.adapter.openai.OpenAiResponsesGateway;
import ai.mindconnect.llm.adapter.openai.OpenAiTranscriptionGateway;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import ai.mindconnect.llm.port.in.LlmTranscription;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmGateway;
import ai.mindconnect.llm.port.out.LlmGatewayRegistry;
import ai.mindconnect.llm.port.out.TranscriptionGateway;
import ai.mindconnect.llm.service.DefaultLlmGatewayRegistry;
import ai.mindconnect.llm.service.RoutingLlmChatService;
import ai.mindconnect.llm.service.RoutingLlmTranscriptionService;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.jackson.JsonMapperFactory;
import okhttp3.OkHttpClient;

/**
 * The models of an installation: the stored LLM configurations, a gateway
 * per provider family behind one chat that routes by the configuration's
 * name, embeddings, transcription — and the runtime's model on top of them.
 * Every provider call is kept as a trace.
 */
public final class LlmPlugin implements Plugin {

    private final String encryptionKey;
    private final EnvVarResolver environment;
    private final ObjectMapper json;

    /**
     * @param encryptionKey the key credentials are kept encrypted with; {@code null} keeps them plain
     * @param environment   where a {@code ${VAR}} in a configuration resolves
     */
    public LlmPlugin(String encryptionKey, EnvVarResolver environment, ObjectMapper json) {
        this.encryptionKey = encryptionKey;
        this.environment = environment;
        this.json = json;
    }

    @Override
    public String name() {
        return "llm";
    }

    @Override
    public void install(PluginRegistry registry) {
        EncryptionHelper encryption = new EncryptionHelper(encryptionKey);
        if (encryptionKey != null) {
            // Its presence is what makes the store keep credentials encrypted.
            registry.provide(EncryptionHelper.class, () -> encryption);
        }
        registry.provide(EnvVarResolver.class, () -> environment);
        registry.provide(OkHttpClient.class, () -> new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build());
        registry.provide(LlmGatewayRegistry.class, () -> {
            OkHttpClient http = registry.get(OkHttpClient.class);
            LlmGateway openAi = new OpenAiCompatibleGateway(http, json, encryption, environment);
            Map<LlmProvider, LlmGateway> gateways = new HashMap<>();
            for (LlmProvider provider : LlmProvider.values()) {
                gateways.put(provider, openAi);   // OpenAI-compatible is the safe default
            }
            // OpenAI itself speaks the Responses API: only there do tools and reasoning go together.
            gateways.put(LlmProvider.OPENAI, new OpenAiResponsesGateway(http, json, encryption, environment));
            gateways.put(LlmProvider.ANTHROPIC, new ClaudeGateway(http, json, encryption, environment));
            gateways.put(LlmProvider.AZURE_OPENAI, new AzureOpenAiGateway(http, json, encryption, environment));
            gateways.put(LlmProvider.GOOGLE_GEMINI, new GeminiGateway(http, json, encryption, environment));
            return new DefaultLlmGatewayRegistry(gateways);
        });
        registry.provide(RoutingLlmChatService.class, () -> new RoutingLlmChatService(
                registry.get(LlmConfigRepository.class), registry.get(LlmGatewayRegistry.class)));
        registry.provide(LlmChat.class, () -> registry.get(RoutingLlmChatService.class));
        registry.provide(LlmEmbeddings.class, () -> new OpenAiEmbeddingsGateway(
                registry.get(OkHttpClient.class), json, encryption, environment));
        registry.provide(TranscriptionGateway.class, () -> new OpenAiTranscriptionGateway(
                registry.get(OkHttpClient.class), json, encryption, environment));
        registry.provide(LlmTranscription.class, () -> new RoutingLlmTranscriptionService(
                registry.get(LlmConfigRepository.class), registry.get(TranscriptionGateway.class)));

        registry.provide(LlmCallTraceListener.class, () -> new RepositoryLlmCallTraceListener(
                registry.get(LlmCallTraceRepository.class), registry.supplier(TurnService.class),
                registry.supplier(SessionStore.class)));
        // Resolved: an alias carries neither a context window nor what the model reads.
        registry.provide(ModelGateway.class, () -> new LlmChatModelGateway(registry.get(LlmChat.class),
                JsonMapperFactory.create(), registry.get(LlmCallTraceListener.class),
                model -> registry.get(LlmConfigRepository.class).findResolvedByName(model),
                registry.find(MediaReader.class).orElseGet(MediaReader::none)));
    }
}
