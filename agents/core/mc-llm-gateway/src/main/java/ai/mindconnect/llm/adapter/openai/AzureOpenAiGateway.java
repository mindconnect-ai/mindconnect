package ai.mindconnect.llm.adapter.openai;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.domain.LlmConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;

/**
 * Azure OpenAI adapter.
 * <p>
 * URL shape: {@code {baseUrl}/openai/deployments/{model}/chat/completions?api-version=2024-02-01}
 * where {@code baseUrl} is {@code https://{resource}.openai.azure.com} and
 * {@code model} is the deployment name stored in {@link LlmConfig#model()}.
 * Auth uses the {@code api-key} header rather than {@code Authorization: Bearer}.
 */
public class AzureOpenAiGateway extends AbstractOpenAiGateway {

    private static final String API_VERSION = "2024-02-01";

    public AzureOpenAiGateway(OkHttpClient httpClient, ObjectMapper objectMapper, EncryptionHelper encryption) {
        super(httpClient, objectMapper, encryption);
    }

    @Override
    protected String endpointUrl(LlmConfig config) {
        return config.baseUrl()
                + "/openai/deployments/" + config.model()
                + "/chat/completions?api-version=" + API_VERSION;
    }

    @Override
    protected String authHeader(LlmConfig config) {
        return config.apiKey();
    }

    @Override
    protected String authHeaderName(LlmConfig config) {
        return "api-key";
    }
}
