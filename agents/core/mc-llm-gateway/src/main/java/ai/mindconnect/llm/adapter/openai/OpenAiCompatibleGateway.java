package ai.mindconnect.llm.adapter.openai;

import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;

/**
 * OpenAI-compatible chat-completions adapter (works with LM Studio, Ollama,
 * Groq, Mistral, vLLM, OpenAI itself, …).
 */
@Component
public class OpenAiCompatibleGateway extends AbstractOpenAiGateway {

    /** Placeholders resolve from the process environment alone — the library and desktop case. */
    public OpenAiCompatibleGateway(OkHttpClient httpClient, ObjectMapper objectMapper, EncryptionHelper encryption) {
        this(httpClient, objectMapper, encryption, EnvVarResolver.system());
    }

    /** @param env where {@code ${VAR}} placeholders in a config resolve from — on a server, the user's, the namespace's and the process's variables in that order */
    public OpenAiCompatibleGateway(OkHttpClient httpClient, ObjectMapper objectMapper, EncryptionHelper encryption,
                                   EnvVarResolver env) {
        super(httpClient, objectMapper, encryption, env);
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
