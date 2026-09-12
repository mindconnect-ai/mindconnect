package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.LlmConfigFormComponent;
import ai.mindconnect.adminui.ui.component.LlmConfigFormComponent.ModelChoices;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.ui.model.UiPage;

import java.util.List;

/**
 * LLM configuration create/edit form. {@code null} config = new
 * config, populated config = edit existing.
 */
public final class LlmConfigFormPage extends AdminPage {

    private final LlmConfig config;
    private final List<LlmConfig> allConfigs;
    private final ModelChoices models;

    public LlmConfigFormPage(LlmConfig config, List<LlmConfig> allConfigs) {
        this(config, allConfigs, ModelChoices.none());
    }

    /**
     * @param models the model list the form's Model field offers — from LM
     *               Studio, from the provider's own listing, or none
     */
    public LlmConfigFormPage(LlmConfig config, List<LlmConfig> allConfigs, ModelChoices models) {
        this.config = config;
        this.allConfigs = allConfigs;
        this.models = models;
    }

    @Override
    public UiPage render() {
        String url = config == null
                ? "/admin/llm-configs/new"
                : "/admin/llm-configs/" + config.id().value() + "/edit";
        return UiPage.of(url, new LlmConfigFormComponent(config, allConfigs, models).render());
    }
}
