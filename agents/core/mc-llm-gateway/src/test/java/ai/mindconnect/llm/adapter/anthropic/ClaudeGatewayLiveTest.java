package ai.mindconnect.llm.adapter.anthropic;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.TestJson;
import ai.mindconnect.llm.adapter.LiveChatGatewayContract;
import ai.mindconnect.llm.adapter.LiveTestEnvironment;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.port.out.LlmGateway;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Map;

/** {@link ClaudeGateway} against api.anthropic.com — see {@link LiveTestEnvironment}. */
@EnabledIf("enabled")
class ClaudeGatewayLiveTest extends LiveChatGatewayContract {

    static boolean enabled() {
        return LiveTestEnvironment.enabled("ANTHROPIC_API_KEY");
    }

    @Override
    protected LlmGateway gateway() {
        return new ClaudeGateway(httpClient(), TestJson.MAPPER, EncryptionHelper.noEncryption());
    }

    @Override
    protected LlmConfig config() {
        return config(LlmProvider.ANTHROPIC, LiveTestEnvironment.claudeModel(), LiveTestEnvironment.apiKey("ANTHROPIC_API_KEY"),
                Map.of());
    }
}
