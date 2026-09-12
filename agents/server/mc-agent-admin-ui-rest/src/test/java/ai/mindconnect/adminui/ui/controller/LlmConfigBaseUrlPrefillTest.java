package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.llm.domain.LlmProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What the form's Base URL shows when the form re-renders. */
class LlmConfigBaseUrlPrefillTest {

    @Test
    void anEmptyBaseUrlIsFilledInWithTheProvidersOwn() {
        assertThat(LlmConfigUiController.baseUrlFor(LlmProvider.MISTRAL, null, true))
                .isEqualTo("https://api.mistral.ai");
        assertThat(LlmConfigUiController.baseUrlFor(LlmProvider.GROQ, "", false))
                .isEqualTo("https://api.groq.com/openai");
    }

    @Test
    void switchingProviderReplacesTheOldProvidersDefault() {
        assertThat(LlmConfigUiController.baseUrlFor(LlmProvider.MISTRAL, "https://api.openai.com", true))
                .as("what sits in the field right after switching is the old default")
                .isEqualTo("https://api.mistral.ai");
    }

    @Test
    void anotherProvidersDefaultStaysWhenTheProviderDidNotChange() {
        assertThat(LlmConfigUiController.baseUrlFor(LlmProvider.OPENAI, "http://localhost:11434", false))
                .as("an OpenAI-compatible config aimed at a local Ollama means it")
                .isEqualTo("http://localhost:11434");
    }

    @Test
    void anEndpointSomebodyTypedIsNeverOverwritten() {
        assertThat(LlmConfigUiController.baseUrlFor(LlmProvider.MISTRAL, "https://my-proxy.internal", true))
                .isEqualTo("https://my-proxy.internal");
        assertThat(LlmConfigUiController.baseUrlFor(LlmProvider.OPENAI, "http://localhost:8080/v1", false))
                .isEqualTo("http://localhost:8080/v1");
    }

    @Test
    void azureKeepsWhateverIsThereBecauseItHasNoDefault() {
        assertThat(LlmConfigUiController.baseUrlFor(LlmProvider.AZURE_OPENAI, null, true)).isNull();
        assertThat(LlmConfigUiController.baseUrlFor(LlmProvider.AZURE_OPENAI,
                "https://mine.openai.azure.com", true)).isEqualTo("https://mine.openai.azure.com");
    }

    @Test
    void withoutAProviderTheFieldIsLeftAlone() {
        assertThat(LlmConfigUiController.baseUrlFor(null, "https://api.openai.com", true))
                .isEqualTo("https://api.openai.com");
        assertThat(LlmConfigUiController.baseUrlFor(null, null, false)).isNull();
    }
}
