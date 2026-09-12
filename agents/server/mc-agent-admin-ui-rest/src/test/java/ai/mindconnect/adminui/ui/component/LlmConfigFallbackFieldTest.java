package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiFieldGroup;
import ai.mindconnect.ui.model.UiNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The "Fallback models (on rate limit)" picker in the chat settings group. */
class LlmConfigFallbackFieldTest {

    private static final LlmConfig CLAUDE = LlmConfig.claude("claude-default", "claude-sonnet-4-6", "k");
    private static final LlmConfig OPENAI = LlmConfig.claude("openai-default", "gpt-5.4-mini", "k");
    private static final LlmConfig GEMINI = LlmConfig.claude("gemini-default", "gemini-2.0-flash", "k");

    private static UiField field(UiFieldGroup group, String id) {
        for (UiNode node : group.getContent()) {
            if (node instanceof UiField f && id.equals(f.getId())) return f;
        }
        return null;
    }

    private static List<String> optionValues(UiField field) {
        return field.getOptions().stream().map(UiField.Option::getValue).toList();
    }

    @Test
    void everyOtherConfigIsAChoiceAndTheConfigItselfIsNot() {
        UiFieldGroup chat = LlmConfigFormComponent.typeGroup(LlmConfigType.CHAT, CLAUDE, null,
                List.of(CLAUDE, OPENAI, GEMINI));

        UiField fallbacks = field(chat, "fallbackModels");
        assertThat(fallbacks.getFieldType()).isEqualTo(UiField.FieldType.MULTISELECT);
        assertThat(fallbacks.isOrderable())
                .as("checkboxes whose order is the fallback order").isTrue();
        assertThat(optionValues(fallbacks)).containsExactly("openai-default", "gemini-default");
        assertThat(fallbacks.getValue()).isEqualTo(List.of());
    }

    @Test
    void thePickedFallbacksShowInTheOrderTheyWereStored() {
        LlmConfig config = CLAUDE.withFallbackModels(List.of("gemini-default", "openai-default"));

        UiField fallbacks = field(
                LlmConfigFormComponent.typeGroup(LlmConfigType.CHAT, config, null,
                        List.of(config, OPENAI, GEMINI)),
                "fallbackModels");

        assertThat(fallbacks.getValue()).isEqualTo(List.of("gemini-default", "openai-default"));
    }

    @Test
    void aFallbackWhoseConfigIsGoneStaysVisibleInsteadOfBeingDroppedOnSave() {
        LlmConfig config = CLAUDE.withFallbackModels(List.of("deleted-config"));

        UiField fallbacks = field(
                LlmConfigFormComponent.typeGroup(LlmConfigType.CHAT, config, null, List.of(config, OPENAI)),
                "fallbackModels");

        assertThat(optionValues(fallbacks)).contains("deleted-config");
        assertThat(fallbacks.getOptions().stream()
                .filter(o -> o.getValue().equals("deleted-config"))
                .map(UiField.Option::getLabel))
                .allMatch(label -> label.contains("no such config"));
        assertThat(fallbacks.getValue()).isEqualTo(List.of("deleted-config"));
    }

    @Test
    void anEmbeddingConfigHasNoFallbackPickerBecauseFallbacksAreAChatThing() {
        UiFieldGroup embedding = LlmConfigFormComponent.typeGroup(LlmConfigType.EMBEDDING, CLAUDE, null,
                List.of(CLAUDE, OPENAI));

        assertThat(field(embedding, "fallbackModels")).isNull();
    }

    @Test
    void onlyConfigsThatEndAtAChatModelAreChoices() {
        LlmConfig transcribe = LlmConfig.speechToText("speech-to-text", LlmProvider.OPENAI,
                "whisper-1", null, "k");
        LlmConfig toOpenAi = LlmConfig.alias("agent-default", "openai-default");
        LlmConfig toSpeech = LlmConfig.alias("stt-default", "speech-to-text");
        LlmConfig toItself = LlmConfig.alias("claude-alias", "claude-default");
        LlmConfig dangling = LlmConfig.alias("broken", "nowhere");

        UiField fallbacks = field(
                LlmConfigFormComponent.typeGroup(LlmConfigType.CHAT, CLAUDE, null,
                        List.of(CLAUDE, OPENAI, transcribe, toOpenAi, toSpeech, toItself, dangling)),
                "fallbackModels");

        assertThat(optionValues(fallbacks))
                .as("no speech-to-text config, no alias to one, none back to the config itself, no broken alias")
                .containsExactly("openai-default", "agent-default");
        assertThat(fallbacks.getOptions()).extracting(UiField.Option::getLabel)
                .containsExactly("openai-default", "agent-default → openai-default");
    }

    @Test
    void aStoredFallbackThatNoLongerQualifiesStaysVisibleAndMarked() {
        LlmConfig transcribe = LlmConfig.speechToText("speech-to-text", LlmProvider.OPENAI,
                "whisper-1", null, "k");
        LlmConfig config = CLAUDE.withFallbackModels(List.of("speech-to-text"));

        UiField fallbacks = field(
                LlmConfigFormComponent.typeGroup(LlmConfigType.CHAT, config, null,
                        List.of(config, transcribe)),
                "fallbackModels");

        assertThat(fallbacks.getOptions()).extracting(UiField.Option::getLabel)
                .containsExactly("speech-to-text (not usable as a fallback)");
        assertThat(fallbacks.getValue()).isEqualTo(List.of("speech-to-text"));
    }
}
