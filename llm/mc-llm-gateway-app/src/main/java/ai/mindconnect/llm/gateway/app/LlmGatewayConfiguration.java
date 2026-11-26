package ai.mindconnect.llm.gateway.app;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmConfigRepository;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmPriceRepository;
import ai.mindconnect.llm.jackson.LlmJsonModule;
import ai.mindconnect.llm.management.api.domain.LlmProvider;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
import ai.mindconnect.llm.management.api.port.out.LlmPriceRepository;
import ai.mindconnect.llm.provider.spi.port.out.LlmGateway;
import ai.mindconnect.llm.providers.anthropic.ClaudeGateway;
import ai.mindconnect.llm.providers.gemini.GeminiGateway;
import ai.mindconnect.llm.providers.openai.AzureOpenAiGateway;
import ai.mindconnect.llm.providers.openai.OpenAiCompatibleGateway;
import ai.mindconnect.llm.providers.openai.OpenAiResponsesGateway;
import ai.mindconnect.llm.runtime.api.port.in.LlmChat;
import ai.mindconnect.llm.service.DefaultLlmGatewayRegistry;
import ai.mindconnect.llm.service.LlmConfigResolver;
import ai.mindconnect.llm.service.LlmConfigService;
import ai.mindconnect.llm.service.LlmPriceService;
import ai.mindconnect.llm.service.RoutingLlmChatService;
import ai.mindconnect.namespace.service.ThreadBoundScopeSupplier;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The gateway as the harness wires it, with configs and prices in memory per
 * namespace. A {@code ${VAR}} in a config resolves in the process environment;
 * keys are kept as given, since nothing here is persisted.
 */
@Configuration
class LlmGatewayConfiguration {

    @Bean
    ThreadBoundScopeSupplier scope() {
        return ThreadBoundScopeSupplier.strict();
    }

    @Bean
    Module llmJsonModule() {
        return new LlmJsonModule();
    }

    @Bean
    LlmConfigRepository llmConfigRepository(ScopeSupplier scope) {
        return new InMemoryLlmConfigRepository(scope);
    }

    @Bean
    LlmPriceRepository llmPriceRepository(ScopeSupplier scope) {
        return new InMemoryLlmPriceRepository(scope);
    }

    @Bean
    LlmPriceService llmPriceService(LlmPriceRepository prices, LlmConfigRepository configs) {
        return new LlmPriceService(prices, configs);
    }

    @Bean
    LlmConfigService llmConfigService(LlmConfigRepository configs, LlmPriceService prices) {
        return new LlmConfigService(configs, prices);
    }

    /** A gateway per provider family, as the harness's LLM plugin chooses them. */
    @Bean
    LlmChat llmChat(LlmConfigRepository configs, ObjectMapper json) {
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        LlmGateway openAiCompatible = new OpenAiCompatibleGateway(http, json);
        Map<LlmProvider, LlmGateway> gateways = new EnumMap<>(LlmProvider.class);
        for (LlmProvider provider : LlmProvider.values()) {
            gateways.put(provider, openAiCompatible);
        }
        gateways.put(LlmProvider.OPENAI, new OpenAiResponsesGateway(http, json));
        gateways.put(LlmProvider.ANTHROPIC, new ClaudeGateway(http, json));
        gateways.put(LlmProvider.AZURE_OPENAI, new AzureOpenAiGateway(http, json));
        gateways.put(LlmProvider.GOOGLE_GEMINI, new GeminiGateway(http, json));
        LlmConfigResolver resolver = new LlmConfigResolver(EnvVarResolver.system(), EncryptionHelper.noEncryption());
        return new RoutingLlmChatService(configs, new DefaultLlmGatewayRegistry(gateways), resolver);
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("mindconnect LLM gateway")
                .description("Chat with a model by the name of its config, and manage configs and prices, per namespace.")
                .version("v1"));
    }
}
