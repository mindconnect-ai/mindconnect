package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.llm.adapter.lmstudio.LmStudioModel;
import ai.mindconnect.llm.adapter.lmstudio.LmStudioModelCatalog;
import ai.mindconnect.llm.domain.LlmCapability;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiFieldGroup;
import ai.mindconnect.ui.model.UiNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The LM Studio model picker: a dropdown of the server's models only when
 * the provider is LM Studio, a text field with the reason when the server
 * does not answer, no API-key field for LM Studio, and the picked model's
 * context length and capabilities landing in the settings group.
 */
class LlmConfigFormLmStudioTest {

    private static final String FORM = "llm-config-new";

    private static final LmStudioModel GPT_OSS = new LmStudioModel(
            "openai/gpt-oss-120b", "openai/gpt-oss-120b", LmStudioModel.Kind.LLM,
            true, 131072, 32768, true, false);
    private static final LmStudioModel GEMMA = new LmStudioModel(
            "google/gemma-4-e4b", "google/gemma-4-e4b", LmStudioModel.Kind.VLM,
            false, 131072, null, true, false);
    private static final LmStudioModel NOMIC = new LmStudioModel(
            "text-embedding-nomic-embed-text-v1.5", "text-embedding-nomic-embed-text-v1.5",
            LmStudioModel.Kind.EMBEDDING, true, 2048, 2048, false, false);

    private static final LmStudioModelCatalog.Catalog CATALOG = new LmStudioModelCatalog.Catalog(
            "http://localhost:1234", List.of(GPT_OSS, GEMMA, NOMIC), null);
    private static final LmStudioModelCatalog.Catalog DOWN = new LmStudioModelCatalog.Catalog(
            "http://localhost:1234", List.of(), "Failed to connect to localhost/127.0.0.1:1234");

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
    void lmStudioGetsADropdownOfChatModelsAndNoKeyField() {
        UiFieldGroup group = LlmConfigFormComponent.baseGroup(false, "CHAT", "LM_STUDIO",
                "openai/gpt-oss-120b", "http://localhost:1234", null, FORM, null, CATALOG);

        UiField model = field(group, "model");
        assertThat(model.getFieldType()).isEqualTo(UiField.FieldType.SELECT);
        assertThat(model.getValue()).isEqualTo("openai/gpt-oss-120b");
        assertThat(optionValues(model))
                .as("the embedding model is not offered to a chat config")
                .containsExactly("", "openai/gpt-oss-120b", "google/gemma-4-e4b");
        assertThat(model.getOptions().get(1).getLabel()).isEqualTo("openai/gpt-oss-120b · 32k loaded (max 128k) · tools");
        assertThat(model.getOnChange().getUrl()).contains("/field-groups").contains("reason=model");

        assertThat(field(group, "apiKey")).as("LM Studio takes no key").isNull();
        UiField baseUrl = field(group, "baseUrl");
        assertThat(baseUrl.getOnChange()).as("a new URL reloads the models").isNotNull();
    }

    @Test
    void anEmbeddingConfigSeesOnlyEmbeddingModels() {
        UiFieldGroup group = LlmConfigFormComponent.baseGroup(false, "EMBEDDING", "LM_STUDIO",
                null, null, null, FORM, null, CATALOG);

        UiField model = field(group, "model");
        assertThat(optionValues(model)).containsExactly("", "text-embedding-nomic-embed-text-v1.5");
        assertThat(model.getValue()).as("nothing picked yet").isEqualTo("");
        assertThat(field(group, "baseUrl").getValue())
                .as("an empty base URL defaults to LM Studio's port")
                .isEqualTo("http://localhost:1234");
    }

    @Test
    void aStoredModelTheServerNoLongerHasStaysSelectable() {
        UiFieldGroup group = LlmConfigFormComponent.baseGroup(false, "CHAT", "LM_STUDIO",
                "qwen/qwen3-32b", "http://localhost:1234", null, FORM, null, CATALOG);

        UiField model = field(group, "model");
        assertThat(model.getValue()).isEqualTo("qwen/qwen3-32b");
        assertThat(optionValues(model)).contains("qwen/qwen3-32b");
        assertThat(model.getOptions().get(model.getOptions().size() - 1).getLabel())
                .contains("not installed in LM Studio");
    }

    @Test
    void whenLmStudioIsDownTheModelIsATextFieldWithTheReason() {
        UiFieldGroup group = LlmConfigFormComponent.baseGroup(false, "CHAT", "LM_STUDIO",
                "openai/gpt-oss-120b", "http://localhost:1234", null, FORM, null, DOWN);

        UiField model = field(group, "model");
        assertThat(model.getFieldType()).isEqualTo(UiField.FieldType.TEXT);
        assertThat(model.getValue()).isEqualTo("openai/gpt-oss-120b");
        assertThat(model.getHint()).contains("did not answer").contains("Failed to connect");
        assertThat(field(group, "apiKey")).isNull();
    }

    @Test
    void otherProvidersKeepTheTextFieldAndTheKey() {
        UiFieldGroup group = LlmConfigFormComponent.baseGroup(false, "CHAT", "OPENAI",
                "gpt-5", null, "${OPENAI_API_KEY}", FORM, null, null);

        UiField model = field(group, "model");
        assertThat(model.getFieldType()).isEqualTo(UiField.FieldType.TEXT);
        assertThat(model.getOnChange()).isNull();
        assertThat(field(group, "apiKey")).isNotNull();
        assertThat(field(group, "baseUrl").getOnChange()).isNull();
    }

    @Test
    void aNewConfigStartsWithNoProviderAndNoPicker() {
        UiFieldGroup group = LlmConfigFormComponent.baseGroup(false, "CHAT", null,
                null, null, null, FORM, null, null);

        UiField provider = field(group, "provider");
        assertThat(provider.getValue()).isEqualTo("");
        assertThat(provider.isRequired()).isTrue();
        assertThat(optionValues(provider).get(0)).as("a blank the admin has to move off").isEqualTo("");
        assertThat(optionValues(provider)).contains("LM_STUDIO", "OPENAI");
        assertThat(field(group, "model").getFieldType()).isEqualTo(UiField.FieldType.TEXT);
        assertThat(field(group, "apiKey")).isNotNull();

        UiFieldGroup chosen = LlmConfigFormComponent.baseGroup(false, "CHAT", "OPENAI",
                null, null, null, FORM, null, null);
        assertThat(optionValues(field(chosen, "provider")))
                .as("once a provider is chosen the blank is gone")
                .doesNotContain("");
    }

    @Test
    void aCatalogForAnotherProviderIsIgnored() {
        // The controller only fetches for LM Studio, but the component must not
        // rely on that: a catalog with a non-LM-Studio provider changes nothing.
        UiFieldGroup group = LlmConfigFormComponent.baseGroup(false, "CHAT", "OLLAMA",
                "llama3", null, null, FORM, null, CATALOG);

        assertThat(field(group, "model").getFieldType()).isEqualTo(UiField.FieldType.TEXT);
        assertThat(field(group, "apiKey")).isNotNull();
    }

    @Test
    void thePickedModelFillsContextWindowAndCapabilities() {
        var prefill = LlmConfigFormComponent.LmStudioPrefill.of(GPT_OSS);
        assertThat(prefill.contextWindowTokens()).as("loaded context wins").isEqualTo(32768);
        assertThat(prefill.capabilities()).containsExactly(LlmCapability.TOOL_CALLING);
        assertThat(prefill.hint()).contains("32768").contains("131072");

        UiFieldGroup chat = LlmConfigFormComponent.typeGroup(LlmConfigType.CHAT, null, prefill);
        assertThat(field(chat, "contextWindowTokens").getValue()).isEqualTo(32768);
        assertThat(field(chat, "contextWindowTokens").getHint()).startsWith("From LM Studio");
        assertThat(field(chat, "capabilities").getValue()).isEqualTo(List.of("TOOL_CALLING"));
        assertThat(field(chat, "capabilities").getHint()).contains("LM Studio");

        var vision = LlmConfigFormComponent.LmStudioPrefill.of(GEMMA);
        assertThat(vision.contextWindowTokens()).as("not loaded: the maximum").isEqualTo(131072);
        assertThat(vision.capabilities()).containsExactlyInAnyOrder(LlmCapability.TOOL_CALLING, LlmCapability.VISION);
        assertThat(vision.hint()).contains("not loaded");

        UiFieldGroup embedding = LlmConfigFormComponent.typeGroup(LlmConfigType.EMBEDDING, null,
                LlmConfigFormComponent.LmStudioPrefill.of(NOMIC));
        assertThat(field(embedding, "contextWindowTokens").getValue()).isEqualTo(2048);
    }

    @Test
    void withoutAPrefillTheConfigsOwnValuesShow() {
        LlmConfig config = LlmConfig.lmStudio("lm", "openai/gpt-oss-120b", "http://localhost:1234");

        UiFieldGroup chat = LlmConfigFormComponent.typeGroup(LlmConfigType.CHAT, config, null);
        assertThat(field(chat, "contextWindowTokens").getValue()).isEqualTo(config.contextWindowTokens());
        assertThat(field(chat, "contextWindowTokens").getHint()).doesNotContain("LM Studio");
        assertThat(field(chat, "capabilities").getValue())
                .isEqualTo(config.effectiveCapabilities().stream().map(Enum::name).toList());
    }

    @Test
    void theEditFormCarriesTheCatalogIntoTheBaseGroup() throws Exception {
        LlmConfig config = LlmConfig.lmStudio("lm", "openai/gpt-oss-120b", "http://localhost:1234");
        assertThat(config.type()).isEqualTo(LlmConfigType.CHAT);
        var mapper = new ObjectMapper();

        String withCatalog = mapper.writeValueAsString(
                new LlmConfigFormComponent(config, List.of(config), CATALOG).render());
        String withoutCatalog = mapper.writeValueAsString(
                new LlmConfigFormComponent(config, List.of(config)).render());

        assertThat(withCatalog).contains("google/gemma-4-e4b").doesNotContain("\"apiKey\"");
        assertThat(withoutCatalog).doesNotContain("google/gemma-4-e4b").doesNotContain("\"apiKey\"");
    }
}
