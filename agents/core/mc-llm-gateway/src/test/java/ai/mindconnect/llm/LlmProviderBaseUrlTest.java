package ai.mindconnect.llm;

import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Every provider knows where it lives, so nobody has to type it from memory. */
class LlmProviderBaseUrlTest {

    @Test
    void everyProviderButAzureHasAnEndpointOfItsOwn() {
        for (LlmProvider provider : LlmProvider.values()) {
            if (provider == LlmProvider.AZURE_OPENAI) {
                assertThat(provider.defaultBaseUrl())
                        .as("an Azure endpoint is the customer's own resource")
                        .isNull();
                continue;
            }
            assertThat(provider.defaultBaseUrl())
                    .as("%s must know its endpoint", provider)
                    .isNotBlank()
                    .matches("https?://.+")
                    .as("%s: the adapters append /v1/… themselves", provider)
                    .doesNotEndWith("/");
        }
    }

    @Test
    void theEndpointsAreTheOnesTheProvidersPublish() {
        assertThat(LlmProvider.OPENAI.defaultBaseUrl()).isEqualTo("https://api.openai.com");
        assertThat(LlmProvider.ANTHROPIC.defaultBaseUrl()).isEqualTo("https://api.anthropic.com");
        assertThat(LlmProvider.MISTRAL.defaultBaseUrl()).isEqualTo("https://api.mistral.ai");
        assertThat(LlmProvider.GROQ.defaultBaseUrl()).isEqualTo("https://api.groq.com/openai");
        assertThat(LlmProvider.DEEPSEEK.defaultBaseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(LlmProvider.TOGETHER.defaultBaseUrl()).isEqualTo("https://api.together.xyz");
        assertThat(LlmProvider.OPENROUTER.defaultBaseUrl()).isEqualTo("https://openrouter.ai/api");
        assertThat(LlmProvider.PERPLEXITY.defaultBaseUrl()).isEqualTo("https://api.perplexity.ai");
        assertThat(LlmProvider.FIREWORKS.defaultBaseUrl()).isEqualTo("https://api.fireworks.ai/inference");
        assertThat(LlmProvider.OLLAMA.defaultBaseUrl()).isEqualTo("http://localhost:11434");
        assertThat(LlmProvider.LM_STUDIO.defaultBaseUrl()).isEqualTo("http://localhost:1234");
        assertThat(LlmProvider.GOOGLE_GEMINI.defaultBaseUrl())
                .isEqualTo("https://generativelanguage.googleapis.com");
    }

    @Test
    void aConfigWithoutABaseUrlFallsBackToTheProvidersOwn() {
        assertThat(LlmProvider.MISTRAL.baseUrlOr(null)).isEqualTo("https://api.mistral.ai");
        assertThat(LlmProvider.MISTRAL.baseUrlOr("  ")).isEqualTo("https://api.mistral.ai");
        assertThat(LlmProvider.MISTRAL.baseUrlOr("http://my-proxy:8080"))
                .as("an endpoint someone chose is never replaced")
                .isEqualTo("http://my-proxy:8080");
    }

    @Test
    void aDefaultUrlIsRecognisedAsOneSoSwitchingProviderMayReplaceIt() {
        assertThat(LlmProvider.isADefaultBaseUrl("https://api.openai.com")).isTrue();
        assertThat(LlmProvider.isADefaultBaseUrl(null)).isTrue();
        assertThat(LlmProvider.isADefaultBaseUrl("")).isTrue();
        assertThat(LlmProvider.isADefaultBaseUrl("https://my-gateway.internal")).isFalse();
    }

    @Test
    void theFactoriesUseTheSameEndpointsRatherThanTheirOwnCopies() {
        assertThat(LlmConfig.claude("c", "m", "k").baseUrl())
                .isEqualTo(LlmProvider.ANTHROPIC.defaultBaseUrl());
        assertThat(LlmConfig.mistral("m", "m", "k").baseUrl())
                .isEqualTo(LlmProvider.MISTRAL.defaultBaseUrl());
        assertThat(LlmConfig.googleGemini("g", "m", "k").baseUrl())
                .isEqualTo(LlmProvider.GOOGLE_GEMINI.defaultBaseUrl());
    }
}
