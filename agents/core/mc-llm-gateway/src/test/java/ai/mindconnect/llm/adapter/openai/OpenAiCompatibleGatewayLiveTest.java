package ai.mindconnect.llm.adapter.openai;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.LiveChatGatewayContract;
import ai.mindconnect.llm.adapter.LiveModels;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.port.out.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Map;

/**
 * {@link OpenAiCompatibleGateway} against api.openai.com over Chat Completions —
 * see {@link LiveModels}. Reasoning stays low so the answer fits the token budget.
 */
@EnabledIf("enabled")
class OpenAiCompatibleGatewayLiveTest extends LiveChatGatewayContract {

    static boolean enabled() {
        return LiveModels.enabled("OPENAI_API_KEY");
    }

    @Override
    protected LlmGateway gateway() {
        return new OpenAiCompatibleGateway(httpClient(), new ObjectMapper(), EncryptionHelper.noEncryption());
    }

    @Override
    protected LlmConfig config() {
        return config(LlmProvider.OPENAI_CHAT_COMPLETIONS, LiveModels.openAiModel(),
                LiveModels.apiKey("OPENAI_API_KEY"), Map.of("reasoning_effort", "low"));
    }
}
