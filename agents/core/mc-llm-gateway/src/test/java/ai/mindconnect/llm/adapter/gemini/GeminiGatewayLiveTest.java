package ai.mindconnect.llm.adapter.gemini;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.LiveChatGatewayContract;
import ai.mindconnect.llm.adapter.LiveModels;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.port.out.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Map;

/** {@link GeminiGateway} against generativelanguage.googleapis.com — see {@link LiveModels}. */
@EnabledIf("enabled")
class GeminiGatewayLiveTest extends LiveChatGatewayContract {

    static boolean enabled() {
        return LiveModels.enabled("GEMINI_API_KEY");
    }

    @Override
    protected LlmGateway gateway() {
        return new GeminiGateway(httpClient(), new ObjectMapper(), EncryptionHelper.noEncryption());
    }

    @Override
    protected LlmConfig config() {
        return config(LlmProvider.GOOGLE_GEMINI, LiveModels.geminiModel(), LiveModels.apiKey("GEMINI_API_KEY"),
                Map.of());
    }
}
