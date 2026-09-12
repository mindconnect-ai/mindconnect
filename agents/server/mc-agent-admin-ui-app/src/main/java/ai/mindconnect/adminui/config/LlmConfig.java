package ai.mindconnect.adminui.config;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.anthropic.ClaudeGateway;
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

import java.util.HashMap;
import java.util.Map;

@Configuration
public class LlmConfig {

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

    /** Speech-to-text over the OpenAI-compatible transcription endpoint (Whisper, Groq, local servers). */
    @Bean
    ai.mindconnect.llm.port.out.TranscriptionGateway transcriptionGateway(
            OkHttpClient okHttpClient, ObjectMapper objectMapper, EncryptionHelper encryptionHelper) {
        return new ai.mindconnect.llm.adapter.openai.OpenAiTranscriptionGateway(okHttpClient, objectMapper, encryptionHelper);
    }

    /** Transcription by config name, aliases followed — the audio counterpart of the chat routing. */
    @Bean
    ai.mindconnect.llm.port.in.LlmTranscription llmTranscription(
            ai.mindconnect.llm.port.out.LlmConfigRepository llmConfigRepository,
            ai.mindconnect.llm.port.out.TranscriptionGateway transcriptionGateway) {
        return new ai.mindconnect.llm.service.RoutingLlmTranscriptionService(llmConfigRepository, transcriptionGateway);
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
        // Every provider speaks the OpenAI API unless it has an adapter of
        // its own — so default them all to it and override the three that
        // differ. A provider added to the enum is then routed by itself.
        for (LlmProvider provider : LlmProvider.values()) {
            gateways.put(provider, openAiCompatibleGateway);
        }
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
