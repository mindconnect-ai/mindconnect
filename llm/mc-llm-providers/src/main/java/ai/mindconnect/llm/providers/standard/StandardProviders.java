package ai.mindconnect.llm.providers.standard;

import ai.mindconnect.llm.management.api.domain.LlmProvider;
import ai.mindconnect.llm.provider.spi.port.out.EmbeddingGateway;
import ai.mindconnect.llm.provider.spi.port.out.LlmGateway;
import ai.mindconnect.llm.provider.spi.port.out.TranscriptionGateway;
import ai.mindconnect.llm.providers.anthropic.ClaudeGateway;
import ai.mindconnect.llm.providers.gemini.GeminiGateway;
import ai.mindconnect.llm.providers.openai.AzureOpenAiGateway;
import ai.mindconnect.llm.providers.openai.OpenAiCompatibleGateway;
import ai.mindconnect.llm.providers.openai.OpenAiEmbeddingsGateway;
import ai.mindconnect.llm.providers.openai.OpenAiResponsesGateway;
import ai.mindconnect.llm.providers.openai.OpenAiTranscriptionGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;

import java.util.EnumMap;
import java.util.Map;

/**
 * Our providers, one per provider family, so that whoever assembles a gateway
 * does not pick the gateway class for each of them. All share one HTTP client
 * and one mapper.
 */
public final class StandardProviders {

    private final OkHttpClient http;
    private final ObjectMapper json;

    public StandardProviders(OkHttpClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    /**
     * A chat gateway for every provider. A provider without its own protocol
     * speaks the OpenAI-compatible one; OpenAI itself speaks the Responses API,
     * the only one where tools and reasoning go together.
     */
    public Map<LlmProvider, LlmGateway> chat() {
        LlmGateway compatible = new OpenAiCompatibleGateway(http, json);
        Map<LlmProvider, LlmGateway> gateways = new EnumMap<>(LlmProvider.class);
        for (LlmProvider provider : LlmProvider.values()) {
            gateways.put(provider, compatible);
        }
        gateways.put(LlmProvider.OPENAI, new OpenAiResponsesGateway(http, json));
        gateways.put(LlmProvider.ANTHROPIC, new ClaudeGateway(http, json));
        gateways.put(LlmProvider.AZURE_OPENAI, new AzureOpenAiGateway(http, json));
        gateways.put(LlmProvider.GOOGLE_GEMINI, new GeminiGateway(http, json));
        return gateways;
    }

    /** Embeddings over the OpenAI-compatible endpoint, whatever the config's provider. */
    public EmbeddingGateway embedding() {
        return new OpenAiEmbeddingsGateway(http, json);
    }

    /** Transcription over the OpenAI-compatible endpoint, whatever the config's provider. */
    public TranscriptionGateway transcription() {
        return new OpenAiTranscriptionGateway(http, json);
    }
}
