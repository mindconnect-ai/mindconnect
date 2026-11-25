package ai.mindconnect.agent.runtime.infrastructure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.runtime.api.port.out.MediaReader;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.agent.runtime.llm.LlmCallTraceListener;
import ai.mindconnect.agent.runtime.llm.LlmChatModelGateway;
import ai.mindconnect.agent.trace.port.out.LlmCallTraceRepository;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.env.EnvVarSource;
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

    /**
     * @param encryptionKey the key credentials are kept encrypted with; {@code null} keeps them plain
     * @param environment   where a {@code ${VAR}} in a configuration resolves; {@code null}: in the
     *                      {@link EnvVarSource}s the plugins contribute, then in the process environment
     */
    public LlmPlugin(String encryptionKey, EnvVarResolver environment) {
        this.encryptionKey = encryptionKey;
        this.environment = environment;
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
        registry.provide(EnvVarResolver.class, () -> environment != null ? environment : contributed(registry));
        registry.provide(OkHttpClient.class, () -> new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build());
        registry.provide(LlmGatewayRegistry.class, () -> {
            OkHttpClient http = registry.get(OkHttpClient.class);
            ObjectMapper json = registry.get(ObjectMapper.class);
            EnvVarResolver env = registry.get(EnvVarResolver.class);
            LlmGateway openAi = new OpenAiCompatibleGateway(http, json, encryption, env);
            Map<LlmProvider, LlmGateway> gateways = new HashMap<>();
            for (LlmProvider provider : LlmProvider.values()) {
                gateways.put(provider, openAi);   // OpenAI-compatible is the safe default
            }
            // OpenAI itself speaks the Responses API: only there do tools and reasoning go together.
            gateways.put(LlmProvider.OPENAI, new OpenAiResponsesGateway(http, json, encryption, env));
            gateways.put(LlmProvider.ANTHROPIC, new ClaudeGateway(http, json, encryption, env));
            gateways.put(LlmProvider.AZURE_OPENAI, new AzureOpenAiGateway(http, json, encryption, env));
            gateways.put(LlmProvider.GOOGLE_GEMINI, new GeminiGateway(http, json, encryption, env));
            return new DefaultLlmGatewayRegistry(gateways);
        });
        registry.provide(RoutingLlmChatService.class, () -> new RoutingLlmChatService(
                registry.get(LlmConfigRepository.class), registry.get(LlmGatewayRegistry.class)));
        registry.provide(LlmChat.class, () -> registry.get(RoutingLlmChatService.class));
        registry.provide(LlmEmbeddings.class, () -> new OpenAiEmbeddingsGateway(registry.get(OkHttpClient.class),
                registry.get(ObjectMapper.class), encryption, registry.get(EnvVarResolver.class)));
        registry.provide(TranscriptionGateway.class, () -> new OpenAiTranscriptionGateway(
                registry.get(OkHttpClient.class), registry.get(ObjectMapper.class), encryption,
                registry.get(EnvVarResolver.class)));
        registry.provide(LlmTranscription.class, () -> new RoutingLlmTranscriptionService(
                registry.get(LlmConfigRepository.class), registry.get(TranscriptionGateway.class)));

        registry.provide(LlmCallTraceListener.class, () -> new RepositoryLlmCallTraceListener(
                registry.get(LlmCallTraceRepository.class), registry.supplier(TurnService.class)));
        // Resolved: an alias carries neither a context window nor what the model reads.
        registry.provide(ModelGateway.class, () -> new LlmChatModelGateway(registry.get(LlmChat.class),
                registry.get(JsonMapperFactory.class).create(), registry.get(LlmCallTraceListener.class),
                model -> registry.get(LlmConfigRepository.class).findResolvedByName(model),
                registry.find(MediaReader.class).orElseGet(MediaReader::none)));
    }

    /** The personal sources first, then the shared ones as contributed, the process environment last. */
    private static EnvVarResolver contributed(PluginRegistry registry) {
        List<EnvVarResolver> sources = new ArrayList<>(registry.all(EnvVarSource.class).stream()
                .map(source -> source.resolver().get())
                .sorted(Comparator.comparing(source -> !source.personal()))
                .toList());
        sources.add(EnvVarResolver.system());
        return EnvVarResolver.chain(sources);
    }
}
