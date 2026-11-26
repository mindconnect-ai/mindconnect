package ai.mindconnect.llm.management.api.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmConfigTest {

    @Test
    void an_openai_config_points_at_openai_and_leaves_the_context_window_unknown() {
        LlmConfig config = LlmConfig.openAi("agent-default", "gpt-5.4-mini", "${OPENAI_API_KEY}");

        assertThat(config.provider()).isEqualTo(LlmProvider.OPENAI);
        assertThat(config.baseUrl()).isEqualTo("https://api.openai.com");
        assertThat(config.model()).isEqualTo("gpt-5.4-mini");
        assertThat(config.apiKey()).isEqualTo("${OPENAI_API_KEY}");
        assertThat(config.contextWindowTokens()).isNull();
        assertThat(config.isAlias()).isFalse();
    }
}
