package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.component.LlmConfigFormComponent;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The LM Studio form has no API-key field, so the controller decides what an
 * LM Studio config stores as its key — and a model picked from the catalog
 * suggests a name for a config that has none.
 */
class LlmConfigUiControllerKeysTest {

    @Test
    void aNewLmStudioConfigGetsThePlaceholderKey() {
        assertThat(LlmConfigUiController.apiKeyFor(LlmProvider.LM_STUDIO, null))
                .isEqualTo(LlmConfigUiController.LM_STUDIO_KEY);
        assertThat(LlmConfigUiController.apiKeyFor(LlmProvider.LM_STUDIO, "  "))
                .isEqualTo(LlmConfigUiController.LM_STUDIO_KEY);
        assertThat(LlmConfigUiController.apiKeyFor(LlmProvider.OPENAI, null)).isNull();
        assertThat(LlmConfigUiController.apiKeyFor(LlmProvider.OPENAI, "sk-x")).isEqualTo("sk-x");
    }

    @Test
    void anUpdatedLmStudioConfigKeepsItsOwnKeyButNotAnotherProviders() {
        LlmConfig lm = LlmConfig.lmStudio("lm", "openai/gpt-oss-120b", "http://localhost:1234")
                .withApiKey("${LM_KEY}");
        assertThat(LlmConfigUiController.apiKeyForUpdate(lm, LlmProvider.LM_STUDIO, null))
                .as("a placeholder on an LM Studio config stays")
                .isEqualTo("${LM_KEY}");

        LlmConfig claude = LlmConfig.claude("c", "claude-sonnet-4-6", "sk-ant-secret");
        assertThat(LlmConfigUiController.apiKeyForUpdate(claude, LlmProvider.LM_STUDIO, null))
                .as("switching to LM Studio does not carry the cloud key along")
                .isEqualTo(LlmConfigUiController.LM_STUDIO_KEY);

        assertThat(LlmConfigUiController.apiKeyForUpdate(claude, LlmProvider.ANTHROPIC, "••••••••"))
                .as("the mask means: keep the stored key")
                .isEqualTo("sk-ant-secret");
        assertThat(LlmConfigUiController.apiKeyForUpdate(claude, LlmProvider.ANTHROPIC, "sk-new"))
                .isEqualTo("sk-new");
    }

    @Test
    void aPickedModelSuggestsAName() {
        assertThat(LlmConfigFormComponent.suggestedName("openai/gpt-oss-120b")).isEqualTo("gpt-oss-120b");
        assertThat(LlmConfigFormComponent.suggestedName("qwen3.8-27b-mlx")).isEqualTo("qwen3.8-27b-mlx");
        assertThat(LlmConfigFormComponent.suggestedName(" ")).isNull();
        assertThat(LlmConfigFormComponent.suggestedName(null)).isNull();
    }
}
