package ai.mindconnect.llm.adapter.anthropic;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.LiveChatGatewayContract;
import ai.mindconnect.llm.adapter.LiveModels;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.port.out.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Map;

/** {@link ClaudeGateway} against api.anthropic.com — see {@link LiveModels}. */
@EnabledIf("enabled")
class ClaudeGatewayLiveTest extends LiveChatGatewayContract {

    static boolean enabled() {
        return LiveModels.enabled("ANTHROPIC_API_KEY");
    }

    @Override
    protected LlmGateway gateway() {
        return new ClaudeGateway(httpClient(), new ObjectMapper(), EncryptionHelper.noEncryption());
    }

    @Override
    protected LlmConfig config() {
        return config(LlmProvider.ANTHROPIC, LiveModels.claudeModel(), LiveModels.apiKey("ANTHROPIC_API_KEY"),
                Map.of());
    }
}
