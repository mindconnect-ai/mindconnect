package ai.mindconnect.llm.providers.openai;

import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.management.api.domain.LlmProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;

/**
 * OpenAI-compatible chat-completions adapter (works with LM Studio, Ollama,
 * Groq, Mistral, vLLM, OpenAI itself, …).
 */
public class OpenAiCompatibleGateway extends AbstractOpenAiGateway {

    public OpenAiCompatibleGateway(OkHttpClient httpClient, ObjectMapper objectMapper) {
        super(httpClient, objectMapper);
    }

    @Override
    protected String endpointUrl(LlmConfig config) {
        // A config may leave baseUrl empty and mean "wherever this provider lives".
        return config.provider().baseUrlOr(config.baseUrl()) + "/v1/chat/completions";
    }

    @Override
    protected String authHeader(LlmConfig config) {
        return "Bearer " + config.apiKey();
    }
}
