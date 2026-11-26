package ai.mindconnect.llm.gateway;

import ai.mindconnect.llm.adapter.env.LlmConfigResolver;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmConfigRepository;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmPriceRepository;
import ai.mindconnect.llm.gateway.service.DefaultLlmGatewayRegistry;
import ai.mindconnect.llm.gateway.service.LlmConfigService;
import ai.mindconnect.llm.gateway.service.LlmPriceService;
import ai.mindconnect.llm.gateway.service.RoutingLlmChatService;
import ai.mindconnect.llm.gateway.service.RoutingLlmEmbeddingService;
import ai.mindconnect.llm.gateway.service.RoutingLlmTranscriptionService;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.management.api.domain.LlmProvider;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
import ai.mindconnect.llm.management.api.port.out.LlmPriceRepository;
import ai.mindconnect.llm.management.api.port.out.LlmScopeSupplier;
import ai.mindconnect.llm.management.api.port.out.SecretResolver;
import ai.mindconnect.llm.provider.spi.port.out.EmbeddingGateway;
import ai.mindconnect.llm.provider.spi.port.out.LlmGateway;
import ai.mindconnect.llm.provider.spi.port.out.TranscriptionGateway;
import ai.mindconnect.llm.runtime.api.port.in.LlmChat;
import ai.mindconnect.llm.runtime.api.port.in.LlmEmbedding;
import ai.mindconnect.llm.runtime.api.port.in.LlmTranscription;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The gateway assembled for a library user, who names providers and configs
 * and leaves the rest to defaults instead of wiring the services by hand: the
 * stores in memory for the {@code local} namespace, and secrets resolved from
 * the process environment.
 *
 * <pre>{@code
 * StandardProviders standard = new StandardProviders(http, json);
 * LlmRuntime llm = LlmRuntime.builder()
 *         .providers(standard.chat())
 *         .config(LlmConfig.openAi("openai-mini", "gpt-5.4-mini", "${OPENAI_API_KEY}"))
 *         .build();
 * llm.chat().chatStreaming(request, handler);
 * }</pre>
 */
public final class LlmRuntime {

    private final LlmChat chat;
    private final LlmEmbedding embedding;
    private final LlmTranscription transcription;
    private final LlmConfigService configs;
    private final LlmPriceService prices;

    private LlmRuntime(Builder builder) {
        LlmConfigRepository configRepository = builder.configs != null
                ? builder.configs : new InMemoryLlmConfigRepository(LlmScopeSupplier.local());
        LlmPriceRepository priceRepository = builder.prices != null
                ? builder.prices : new InMemoryLlmPriceRepository(LlmScopeSupplier.local());
        SecretResolver secrets = builder.secrets != null ? builder.secrets : LlmConfigResolver::resolved;
        this.prices = new LlmPriceService(priceRepository, configRepository);
        this.configs = new LlmConfigService(configRepository, prices);
        builder.initialConfigs.forEach(configs::save);
        this.chat = new RoutingLlmChatService(configRepository, new DefaultLlmGatewayRegistry(builder.providers),
                secrets);
        this.embedding = builder.embedding == null ? null
                : new RoutingLlmEmbeddingService(configRepository, builder.embedding, secrets);
        this.transcription = builder.transcription == null ? null
                : new RoutingLlmTranscriptionService(configRepository, builder.transcription, secrets);
    }

    public static Builder builder() {
        return new Builder();
    }

    public LlmChat chat() {
        return chat;
    }

    /** @throws IllegalStateException if the builder got no embedding gateway */
    public LlmEmbedding embedding() {
        if (embedding == null) throw new IllegalStateException("No embedding gateway was given to the builder");
        return embedding;
    }

    /** @throws IllegalStateException if the builder got no transcription gateway */
    public LlmTranscription transcription() {
        if (transcription == null) throw new IllegalStateException("No transcription gateway was given to the builder");
        return transcription;
    }

    /** Saving and deleting configs, with their prices following their names. */
    public LlmConfigService configs() {
        return configs;
    }

    public LlmPriceService prices() {
        return prices;
    }

    /** Collects what differs from the defaults; {@link #build} assembles it. */
    public static final class Builder {

        private final Map<LlmProvider, LlmGateway> providers = new EnumMap<>(LlmProvider.class);
        private final List<LlmConfig> initialConfigs = new ArrayList<>();
        private EmbeddingGateway embedding;
        private TranscriptionGateway transcription;
        private LlmConfigRepository configs;
        private LlmPriceRepository prices;
        private SecretResolver secrets;

        private Builder() {
        }

        /** The chat gateway for one provider; a config whose provider has none fails on call. */
        public Builder provider(LlmProvider provider, LlmGateway gateway) {
            providers.put(provider, gateway);
            return this;
        }

        /** Several chat gateways at once, such as {@code StandardProviders.chat()}. */
        public Builder providers(Map<LlmProvider, LlmGateway> gateways) {
            providers.putAll(gateways);
            return this;
        }

        public Builder embedding(EmbeddingGateway gateway) {
            this.embedding = gateway;
            return this;
        }

        public Builder transcription(TranscriptionGateway gateway) {
            this.transcription = gateway;
            return this;
        }

        /** A config saved on {@link #build}, the way {@link LlmRuntime#configs()} saves it. */
        public Builder config(LlmConfig config) {
            initialConfigs.add(config);
            return this;
        }

        /** Where configs are kept, in place of memory. */
        public Builder configs(LlmConfigRepository repository) {
            this.configs = repository;
            return this;
        }

        /** Where prices are kept, in place of memory. */
        public Builder prices(LlmPriceRepository repository) {
            this.prices = repository;
            return this;
        }

        /** How a config's placeholders and keys resolve, in place of the process environment. */
        public Builder secrets(SecretResolver resolver) {
            this.secrets = resolver;
            return this;
        }

        public LlmRuntime build() {
            return new LlmRuntime(this);
        }
    }
}
