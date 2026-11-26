package ai.mindconnect.llm.providers.standard;

import ai.mindconnect.llm.management.api.domain.LlmProvider;
import ai.mindconnect.llm.providers.TestJson;
import ai.mindconnect.llm.providers.anthropic.ClaudeGateway;
import ai.mindconnect.llm.providers.openai.OpenAiCompatibleGateway;
import ai.mindconnect.llm.providers.openai.OpenAiResponsesGateway;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StandardProvidersTest {

    private final StandardProviders standard = new StandardProviders(new OkHttpClient(), TestJson.MAPPER);

    @Test
    void everyProviderHasAChatGateway() {
        assertThat(standard.chat()).containsOnlyKeys(LlmProvider.values());
    }

    @Test
    void openAiSpeaksTheResponsesApiAndAProviderWithoutItsOwnProtocolTheCompatibleOne() {
        assertThat(standard.chat().get(LlmProvider.OPENAI)).isInstanceOf(OpenAiResponsesGateway.class);
        assertThat(standard.chat().get(LlmProvider.ANTHROPIC)).isInstanceOf(ClaudeGateway.class);
        assertThat(standard.chat().get(LlmProvider.LM_STUDIO)).isInstanceOf(OpenAiCompatibleGateway.class);
    }
}
