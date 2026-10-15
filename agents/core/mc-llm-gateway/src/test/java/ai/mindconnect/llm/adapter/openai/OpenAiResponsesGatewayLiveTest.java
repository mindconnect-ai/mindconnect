package ai.mindconnect.llm.adapter.openai;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.TestJson;
import ai.mindconnect.llm.adapter.LiveChatGatewayContract;
import ai.mindconnect.llm.adapter.LiveTestEnvironment;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.port.out.LlmGateway;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Map;

/**
 * {@link OpenAiResponsesGateway} against api.openai.com over the Responses API —
 * see {@link LiveTestEnvironment}. Reasoning stays low so the answer fits the token budget.
 */
@EnabledIf("enabled")
class OpenAiResponsesGatewayLiveTest extends LiveChatGatewayContract {

    static boolean enabled() {
        return LiveTestEnvironment.enabled("OPENAI_API_KEY");
    }

    @Override
    protected LlmGateway gateway() {
        return new OpenAiResponsesGateway(httpClient(), TestJson.MAPPER, EncryptionHelper.noEncryption());
    }

    @Override
    protected LlmConfig config() {
        return config(LlmProvider.OPENAI, LiveTestEnvironment.openAiModel(), LiveTestEnvironment.apiKey("OPENAI_API_KEY"),
                Map.of("reasoning_effort", "low"));
    }
}
