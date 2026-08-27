package ai.mindconnect.cli;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.anthropic.ClaudeGateway;

import ai.mindconnect.llm.adapter.file.FileLlmConfigRepository;
import ai.mindconnect.llm.adapter.gemini.GeminiGateway;
import ai.mindconnect.llm.adapter.openai.AzureOpenAiGateway;
import ai.mindconnect.llm.adapter.openai.OpenAiCompatibleGateway;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.service.DefaultLlmGatewayRegistry;
import ai.mindconnect.llm.service.RoutingLlmChatService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Configuration
public class CliConfig {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    // FOR REMOTE
    @Bean
    OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }
    // FOR LOCAL
    @Bean
    LlmConfigRepository llmConfigRepository(
            @Value("${mindconnect.data.base-dir:data}") String baseDir) {
        return new FileLlmConfigRepository(Path.of(baseDir));
    }

    @Bean
    OpenAiCompatibleGateway openAiCompatibleGateway(OkHttpClient okHttpClient, ObjectMapper objectMapper) {
        return new OpenAiCompatibleGateway(okHttpClient, objectMapper, EncryptionHelper.noEncryption());
    }

    @Bean
    ClaudeGateway claudeGateway(OkHttpClient okHttpClient, ObjectMapper objectMapper) {
        return new ClaudeGateway(okHttpClient, objectMapper, EncryptionHelper.noEncryption());
    }

    @Bean
    AzureOpenAiGateway azureOpenAiGateway(OkHttpClient okHttpClient, ObjectMapper objectMapper) {
        return new AzureOpenAiGateway(okHttpClient, objectMapper, EncryptionHelper.noEncryption());
    }

    @Bean
    GeminiGateway geminiGateway(OkHttpClient okHttpClient, ObjectMapper objectMapper) {
        return new GeminiGateway(okHttpClient, objectMapper, EncryptionHelper.noEncryption());
    }

    @Bean
    RoutingLlmChatService llmService(LlmConfigRepository llmConfigRepository,
                                     OpenAiCompatibleGateway openAiCompatibleGateway,
                                     ClaudeGateway claudeGateway,
                                     AzureOpenAiGateway azureOpenAiGateway,
                                     GeminiGateway geminiGateway) {
        var gateways = new java.util.HashMap<LlmProvider, ai.mindconnect.llm.port.out.LlmGateway>();
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
        gateways.put(LlmProvider.ANTHROPIC,     claudeGateway);
        gateways.put(LlmProvider.AZURE_OPENAI,  azureOpenAiGateway);
        gateways.put(LlmProvider.GOOGLE_GEMINI, geminiGateway);
        var registry = new DefaultLlmGatewayRegistry(gateways);
        return new RoutingLlmChatService(llmConfigRepository, registry);
    }
}
