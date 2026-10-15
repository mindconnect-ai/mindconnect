package ai.mindconnect.llm.adapter.gemini;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.TestJson;
import ai.mindconnect.llm.adapter.LiveChatGatewayContract;
import ai.mindconnect.llm.adapter.LiveTestEnvironment;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.port.out.LlmGateway;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Map;

/** {@link GeminiGateway} against generativelanguage.googleapis.com — see {@link LiveTestEnvironment}. */
@EnabledIf("enabled")
class GeminiGatewayLiveTest extends LiveChatGatewayContract {

    static boolean enabled() {
        return LiveTestEnvironment.enabled("GEMINI_API_KEY");
    }

    @Override
    protected LlmGateway gateway() {
        return new GeminiGateway(httpClient(), TestJson.MAPPER, EncryptionHelper.noEncryption());
    }

    @Override
    protected LlmConfig config() {
        return config(LlmProvider.GOOGLE_GEMINI, LiveTestEnvironment.geminiModel(), LiveTestEnvironment.apiKey("GEMINI_API_KEY"),
                Map.of());
    }
}
