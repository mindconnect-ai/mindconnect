package ai.mindconnect.llm;

import ai.mindconnect.llm.domain.LlmConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** How {@code fallbackModels} is normalised and how it travels through JSON. */
class LlmConfigFallbackModelsTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void aConfigWithoutFallbacksHasAnEmptyListRatherThanNull() {
        LlmConfig config = LlmConfig.claude("claude", "claude-sonnet-4-6", "key");

        assertThat(config.fallbackModels()).isEmpty();
        assertThat(config.hasFallbackModels()).isFalse();
    }

    @Test
    void blankAndDuplicateNamesAreDroppedAndOrderIsKept() {
        LlmConfig config = LlmConfig.claude("claude", "claude-sonnet-4-6", "key")
                .withFallbackModels(Arrays.asList(" gpt ", "", null, "gemini", "gpt"));

        assertThat(config.fallbackModels()).containsExactly("gpt", "gemini");
        assertThat(config.hasFallbackModels()).isTrue();
    }

    @Test
    void fallbackModelsSurviveAJsonRoundTrip() throws Exception {
        LlmConfig config = LlmConfig.claude("claude", "claude-sonnet-4-6", "key")
                .withFallbackModels(List.of("gpt-default", "gemini-default"));

        String serialised = json.writeValueAsString(config);
        assertThat(serialised).contains("\"fallbackModels\"");

        assertThat(json.readValue(serialised, LlmConfig.class).fallbackModels())
                .containsExactly("gpt-default", "gemini-default");
    }

    @Test
    void aConfigStoredBeforeTheFieldExistedReadsAsNoFallbacks() throws Exception {
        String legacy = """
                {"id":"11111111-1111-1111-1111-111111111111","name":"legacy",
                 "provider":"OPENAI","model":"gpt-4o","baseUrl":"https://api.openai.com",
                 "apiKey":"k","defaultTemperature":0.7,"maxOutputTokens":4096}
                """;

        assertThat(json.readValue(legacy, LlmConfig.class).fallbackModels()).isEmpty();
    }
}
