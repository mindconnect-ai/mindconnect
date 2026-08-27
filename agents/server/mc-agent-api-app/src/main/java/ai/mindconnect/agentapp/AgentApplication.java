package ai.mindconnect.agentapp;

import ai.mindconnect.agent.adapter.config.DefaultAgentRuntimeConfig;
import ai.mindconnect.agent.adapter.config.TodoToolsConfig;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.anthropic.ClaudeGateway;
import ai.mindconnect.llm.adapter.file.EncryptingLlmConfigRepository;
import ai.mindconnect.llm.adapter.file.FileLlmConfigRepository;
import ai.mindconnect.llm.adapter.gemini.GeminiGateway;
import ai.mindconnect.llm.adapter.openai.AzureOpenAiGateway;
import ai.mindconnect.llm.adapter.openai.OpenAiCompatibleGateway;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.service.DefaultLlmGatewayRegistry;
import ai.mindconnect.llm.service.RoutingLlmChatService;
import ai.mindconnect.message.adapter.file.MessageRepositoryConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
@ComponentScan(basePackages = {
    "ai.mindconnect.agentapp",
    "ai.mindconnect.agentrest"
})
@Import({
    MessageRepositoryConfig.class,
    DefaultAgentRuntimeConfig.class,
    TodoToolsConfig.class
})
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }

    @Bean
    OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)  // no timeout for SSE streams
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * The secret key encrypts stored LLM credentials. It is required and has
     * no default on purpose: a hard-coded fallback would mean credentials are
     * "encrypted" with a publicly known key. Provide a strong, private value
     * via {@code mindconnect.encryption.secret-key} (env var
     * {@code MINDCONNECT_ENCRYPTION_SECRET_KEY}).
     */
    @Bean
    EncryptionHelper encryptionHelper(
            @Value("${mindconnect.encryption.secret-key:}") String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                "mindconnect.encryption.secret-key is not set. It encrypts stored LLM "
                + "credentials, so there is no default. Set it via the "
                + "MINDCONNECT_ENCRYPTION_SECRET_KEY environment variable (use a strong, "
                + "private 32-character value).");
        }
        return new EncryptionHelper(secretKey);
    }

    @Bean
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

    @Bean
    RoutingLlmChatService llmService(LlmConfigRepository llmConfigRepository,
                                     OpenAiCompatibleGateway openAiCompatibleGateway,
                                     ClaudeGateway claudeGateway,
                                     AzureOpenAiGateway azureOpenAiGateway,
                                     GeminiGateway geminiGateway) {
        var gateways = new HashMap<LlmProvider, ai.mindconnect.llm.port.out.LlmGateway>();
        gateways.put(LlmProvider.LM_STUDIO,    openAiCompatibleGateway);
        gateways.put(LlmProvider.OPENAI,       openAiCompatibleGateway);
        gateways.put(LlmProvider.GROQ,         openAiCompatibleGateway);
        gateways.put(LlmProvider.OLLAMA,       openAiCompatibleGateway);
        gateways.put(LlmProvider.MISTRAL,      openAiCompatibleGateway);
        gateways.put(LlmProvider.DEEPSEEK,     openAiCompatibleGateway);
        gateways.put(LlmProvider.TOGETHER,     openAiCompatibleGateway);
        gateways.put(LlmProvider.OPENROUTER,   openAiCompatibleGateway);
        gateways.put(LlmProvider.PERPLEXITY,   openAiCompatibleGateway);
        gateways.put(LlmProvider.FIREWORKS,    openAiCompatibleGateway);
        gateways.put(LlmProvider.ANTHROPIC,    claudeGateway);
        gateways.put(LlmProvider.AZURE_OPENAI, azureOpenAiGateway);
        gateways.put(LlmProvider.GOOGLE_GEMINI, geminiGateway);
        var registry = new DefaultLlmGatewayRegistry(gateways);
        return new RoutingLlmChatService(llmConfigRepository, registry);
    }
}
