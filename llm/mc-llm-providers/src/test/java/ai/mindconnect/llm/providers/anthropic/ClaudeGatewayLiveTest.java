package ai.mindconnect.llm.providers.anthropic;

import ai.mindconnect.llm.providers.TestJson;
import ai.mindconnect.llm.providers.LiveChatGatewayContract;
import ai.mindconnect.llm.providers.LiveTestEnvironment;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.management.api.domain.LlmProvider;
import ai.mindconnect.llm.provider.spi.port.out.LlmGateway;
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
        return new ClaudeGateway(httpClient(), TestJson.MAPPER);
    }

    @Override
    protected LlmConfig config() {
        return config(LlmProvider.ANTHROPIC, LiveTestEnvironment.claudeModel(), LiveTestEnvironment.apiKey("ANTHROPIC_API_KEY"),
                Map.of());
    }
}
