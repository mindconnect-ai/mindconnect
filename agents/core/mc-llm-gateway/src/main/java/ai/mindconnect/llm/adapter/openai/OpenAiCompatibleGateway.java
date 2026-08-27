package ai.mindconnect.llm.adapter.openai;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.domain.LlmConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;

/**
 * OpenAI-compatible chat-completions adapter (works with LM Studio, Ollama,
 * Groq, Mistral, vLLM, OpenAI itself, …).
 */
@Component
public class OpenAiCompatibleGateway extends AbstractOpenAiGateway {

    public OpenAiCompatibleGateway(OkHttpClient httpClient, ObjectMapper objectMapper, EncryptionHelper encryption) {
        super(httpClient, objectMapper, encryption);
    }

    @Override
    protected String endpointUrl(LlmConfig config) {
        return config.baseUrl() + "/v1/chat/completions";
    }

    @Override
    protected String authHeader(LlmConfig config) {
        return "Bearer " + config.apiKey();
    }
}
