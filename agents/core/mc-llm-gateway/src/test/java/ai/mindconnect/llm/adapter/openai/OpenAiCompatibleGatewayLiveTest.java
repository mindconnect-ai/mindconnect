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
 * {@link OpenAiCompatibleGateway} against api.openai.com over Chat Completions —
 * see {@link LiveTestEnvironment}. Reasoning stays low so the answer fits the token budget.
 */
@EnabledIf("enabled")
class OpenAiCompatibleGatewayLiveTest extends LiveChatGatewayContract {

    static boolean enabled() {
        return LiveTestEnvironment.enabled("OPENAI_API_KEY");
    }

    @Override
    protected LlmGateway gateway() {
        return new OpenAiCompatibleGateway(httpClient(), TestJson.MAPPER, EncryptionHelper.noEncryption());
    }

    @Override
    protected LlmConfig config() {
        return config(LlmProvider.OPENAI_CHAT_COMPLETIONS, LiveTestEnvironment.openAiModel(),
                LiveTestEnvironment.apiKey("OPENAI_API_KEY"), Map.of("reasoning_effort", "low"));
    }
}
