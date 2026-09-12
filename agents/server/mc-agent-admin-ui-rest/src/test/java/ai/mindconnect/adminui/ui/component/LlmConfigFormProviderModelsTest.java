package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.ui.component.LlmConfigFormComponent.ModelChoices;
import ai.mindconnect.llm.adapter.ProviderModelCatalog;
import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiFieldGroup;
import ai.mindconnect.ui.model.UiNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Model dropdown for the providers that publish a {@code /v1/models}
 * listing — Mistral, Groq, OpenAI and the rest — and the endpoint the form
 * fills in for them.
 */
class LlmConfigFormProviderModelsTest {

    private static final String FORM = "llm-config-new";

    private static final ProviderModelCatalog.Catalog MISTRAL = new ProviderModelCatalog.Catalog(
            "https://api.mistral.ai",
            List.of(ProviderModelCatalog.Model.of("mistral-large-latest", "mistral-large-latest", null),
                    ProviderModelCatalog.Model.of("mistral-embed", "mistral-embed", LlmConfigType.EMBEDDING)),
            null);

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
    void aListedProviderGetsADropdownAndKeepsItsApiKeyField() {
        UiFieldGroup group = LlmConfigFormComponent.baseGroup(false, "CHAT", "MISTRAL",
                "mistral-large-latest", null, "${MISTRAL_API_KEY}", FORM, null,
                ModelChoices.of(MISTRAL));

        UiField model = field(group, "model");
        assertThat(model.getFieldType()).isEqualTo(UiField.FieldType.SELECT);
        assertThat(optionValues(model))
                .as("the embedding model is not offered to a chat config")
                .containsExactly("", "mistral-large-latest");
        assertThat(model.getOnChange().getUrl()).contains("reason=model");
        assertThat(field(group, "apiKey")).as("a cloud provider needs its key").isNotNull();
        assertThat(field(group, "baseUrl").getValue()).isEqualTo("https://api.mistral.ai");
    }

    @Test
    void anEmbeddingConfigSeesTheEmbeddingModelsAndTheUnclassifiedOnes() {
        UiFieldGroup group = LlmConfigFormComponent.baseGroup(false, "EMBEDDING", "MISTRAL",
                null, null, "k", FORM, null, ModelChoices.of(MISTRAL));

        assertThat(optionValues(field(group, "model")))
                .as("a listing that does not say what a model is must not hide it")
                .containsExactly("", "mistral-large-latest", "mistral-embed");
    }

    @Test
    void aStoredModelTheProviderNoLongerListsStaysSelectable() {
        UiFieldGroup group = LlmConfigFormComponent.baseGroup(false, "CHAT", "MISTRAL",
                "mistral-medium-2312", null, "k", FORM, null, ModelChoices.of(MISTRAL));

        UiField model = field(group, "model");
        assertThat(model.getValue()).isEqualTo("mistral-medium-2312");
        assertThat(model.getOptions().get(model.getOptions().size() - 1).getLabel())
                .contains("not in the provider's list");
    }

    @Test
    void aListingThatFailedLeavesATextFieldCarryingTheReason() {
        var refused = ProviderModelCatalog.Catalog.unavailable("https://api.mistral.ai",
                "HTTP 401 — the API key is not accepted");

        UiField model = field(LlmConfigFormComponent.baseGroup(false, "CHAT", "MISTRAL",
                "mistral-large-latest", null, "wrong", FORM, null, ModelChoices.of(refused)), "model");

        assertThat(model.getFieldType()).isEqualTo(UiField.FieldType.TEXT);
        assertThat(model.getValue()).isEqualTo("mistral-large-latest");
        assertThat(model.getHint()).contains("the API key is not accepted");
    }

    @Test
    void theKeyFieldReloadsTheListBecauseTheListingNeedsIt() {
        UiField apiKey = field(LlmConfigFormComponent.baseGroup(false, "CHAT", "MISTRAL",
                null, null, null, FORM, null, ModelChoices.of(MISTRAL)), "apiKey");

        assertThat(apiKey.getOnChange()).isNotNull();
        assertThat(apiKey.getOnChange().getUrl()).contains("/field-groups");
    }

    @Test
    void azureKeepsATypedModelBecauseItsConfigsNameDeployments() {
        UiFieldGroup group = LlmConfigFormComponent.baseGroup(false, "CHAT", "AZURE_OPENAI",
                "gpt-4o", null, "k", FORM, null, ModelChoices.none());

        assertThat(field(group, "model").getFieldType()).isEqualTo(UiField.FieldType.TEXT);
        assertThat(field(group, "baseUrl").getValue())
                .as("nobody can guess an Azure resource")
                .isNull();
        assertThat(field(group, "baseUrl").getHint()).contains("openai.azure.com");
        assertThat(field(group, "apiKey").getOnChange())
                .as("no listing to reload")
                .isNull();
    }
}
