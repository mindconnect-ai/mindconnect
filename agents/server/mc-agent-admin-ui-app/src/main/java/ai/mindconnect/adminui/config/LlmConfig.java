package ai.mindconnect.adminui.config;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.anthropic.ClaudeGateway;
import ai.mindconnect.llm.adapter.file.EncryptingLlmConfigRepository;
import ai.mindconnect.llm.adapter.file.FileLlmConfigRepository;
import ai.mindconnect.llm.adapter.gemini.GeminiGateway;
import ai.mindconnect.llm.adapter.openai.AzureOpenAiGateway;
import ai.mindconnect.llm.adapter.openai.OpenAiCompatibleGateway;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmGateway;
import ai.mindconnect.llm.service.DefaultLlmGatewayRegistry;
import ai.mindconnect.llm.service.RoutingLlmChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class LlmConfig {

    @Bean
    @ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "file", matchIfMissing = true)
    LlmConfigRepository llmConfigRepository(
            @Value("${mindconnect.data.base-dir:data}") String baseDir,
            EncryptionHelper encryptionHelper) {
        return new EncryptingLlmConfigRepository(
                new FileLlmConfigRepository(Path.of(baseDir)), encryptionHelper);
    }

    @Bean
    OpenAiCompatibleGateway openAiCompatibleGateway(OkHttpClient okHttpClient, ObjectMapper objectMapper,
                                                     EncryptionHelper encryptionHelper) {
        return new OpenAiCompatibleGateway(okHttpClient, objectMapper, encryptionHelper);
    }

    /** Embeddings over the OpenAI-compatible endpoint (LM Studio, Ollama, OpenAI). */
    @Bean
    ai.mindconnect.llm.port.in.LlmEmbeddings llmEmbeddings(OkHttpClient okHttpClient, ObjectMapper objectMapper,
                                                           EncryptionHelper encryptionHelper) {
        return new ai.mindconnect.llm.adapter.openai.OpenAiEmbeddingsGateway(okHttpClient, objectMapper, encryptionHelper);
    }

    @Bean
    ClaudeGateway claudeGateway(OkHttpClient okHttpClient, ObjectMapper objectMapper,
                                 EncryptionHelper encryptionHelper) {
        return new ClaudeGateway(okHttpClient, objectMapper, encryptionHelper);
    }

    @Bean
    AzureOpenAiGateway azureOpenAiGateway(OkHttpClient okHttpClient, ObjectMapper objectMapper,
                                           EncryptionHelper encryptionHelper) {
        return new AzureOpenAiGateway(okHttpClient, objectMapper, encryptionHelper);
    }

    @Bean
    GeminiGateway geminiGateway(OkHttpClient okHttpClient, ObjectMapper objectMapper,
                                 EncryptionHelper encryptionHelper) {
        return new GeminiGateway(okHttpClient, objectMapper, encryptionHelper);
    }

    /**
     * Exposes the provider→gateway map as its own bean so anything in the
     * admin-ui that needs to talk to a specific {@link LlmConfig}'s
     * provider (the "Test config" button, future ad-hoc probes) can inject
     * the registry directly instead of going through
     * {@link RoutingLlmChatService} (which routes by config-name only).
     */
    @Bean
    ai.mindconnect.llm.port.out.LlmGatewayRegistry llmGatewayRegistry(
                                     OpenAiCompatibleGateway openAiCompatibleGateway,
                                     ClaudeGateway claudeGateway,
                                     AzureOpenAiGateway azureOpenAiGateway,
                                     GeminiGateway geminiGateway) {
        Map<LlmProvider, LlmGateway> gateways = new HashMap<>();
        gateways.put(LlmProvider.LM_STUDIO,     openAiCompatibleGateway);
        gateways.put(LlmProvider.OPENAI,        openAiCompatibleGateway);
        gateways.put(LlmProvider.GROQ,          openAiCompatibleGateway);
        gateways.put(LlmProvider.OLLAMA,        openAiCompatibleGateway);
        gateways.put(LlmProvider.MISTRAL,       openAiCompatibleGateway);
        gateways.put(LlmProvider.DEEPSEEK,      openAiCompatibleGateway);
        gateways.put(LlmProvider.TOGETHER,      openAiCompatibleGateway);
        gateways.put(LlmProvider.OPENROUTER,    openAiCompatibleGateway);
        gateways.put(LlmProvider.PERPLEXITY,    openAiCompatibleGateway);
        gateways.put(LlmProvider.FIREWORKS,     openAiCompatibleGateway);
        gateways.put(LlmProvider.ANTHROPIC,     claudeGateway);
        gateways.put(LlmProvider.AZURE_OPENAI,  azureOpenAiGateway);
        gateways.put(LlmProvider.GOOGLE_GEMINI, geminiGateway);
        return new DefaultLlmGatewayRegistry(gateways);
    }

    @Bean
    RoutingLlmChatService llmChatService(LlmConfigRepository llmConfigRepository,
                                         ai.mindconnect.llm.port.out.LlmGatewayRegistry gatewayRegistry) {
        return new RoutingLlmChatService(llmConfigRepository, gatewayRegistry);
    }
}
