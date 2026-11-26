package ai.mindconnect.agent.runtime.infrastructure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
import ai.mindconnect.llm.providers.standard.StandardProviders;
import ai.mindconnect.llm.runtime.api.port.in.LlmChat;
import ai.mindconnect.llm.provider.spi.port.out.EmbeddingGateway;
import ai.mindconnect.llm.runtime.api.port.in.LlmEmbedding;
import ai.mindconnect.llm.gateway.service.RoutingLlmEmbeddingService;
import ai.mindconnect.llm.runtime.api.port.in.LlmTranscription;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
import ai.mindconnect.llm.gateway.service.LlmGatewayRegistry;
import ai.mindconnect.llm.provider.spi.port.out.TranscriptionGateway;
import ai.mindconnect.llm.gateway.service.DefaultLlmGatewayRegistry;
import ai.mindconnect.llm.adapter.env.LlmConfigResolver;
import ai.mindconnect.llm.gateway.service.RoutingLlmChatService;
import ai.mindconnect.llm.gateway.service.RoutingLlmTranscriptionService;
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
        registry.provide(LlmConfigResolver.class, () -> new LlmConfigResolver(registry.get(EnvVarResolver.class),
                encryption));
        registry.provide(StandardProviders.class, () -> new StandardProviders(registry.get(OkHttpClient.class),
                registry.get(ObjectMapper.class)));
        registry.provide(LlmGatewayRegistry.class,
                () -> new DefaultLlmGatewayRegistry(registry.get(StandardProviders.class).chat()));
        registry.provide(RoutingLlmChatService.class, () -> new RoutingLlmChatService(
                registry.get(LlmConfigRepository.class), registry.get(LlmGatewayRegistry.class),
                registry.get(LlmConfigResolver.class)));
        registry.provide(LlmChat.class, () -> registry.get(RoutingLlmChatService.class));
        registry.provide(EmbeddingGateway.class, () -> registry.get(StandardProviders.class).embedding());
        registry.provide(LlmEmbedding.class, () -> new RoutingLlmEmbeddingService(
                registry.get(LlmConfigRepository.class), registry.get(EmbeddingGateway.class),
                registry.get(LlmConfigResolver.class)));
        registry.provide(TranscriptionGateway.class, () -> registry.get(StandardProviders.class).transcription());
        registry.provide(LlmTranscription.class, () -> new RoutingLlmTranscriptionService(
                registry.get(LlmConfigRepository.class), registry.get(TranscriptionGateway.class),
                registry.get(LlmConfigResolver.class)));

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
