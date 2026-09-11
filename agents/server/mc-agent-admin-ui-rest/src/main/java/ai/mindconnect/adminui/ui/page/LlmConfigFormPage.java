package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.LlmConfigFormComponent;
import ai.mindconnect.llm.adapter.lmstudio.LmStudioModelCatalog;
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
    private final LmStudioModelCatalog.Catalog lmStudio;

    public LlmConfigFormPage(LlmConfig config, List<LlmConfig> allConfigs) {
        this(config, allConfigs, null);
    }

    /**
     * @param lmStudio the LM Studio catalog for an LM Studio config's base
     *                 URL, {@code null} for any other config
     */
    public LlmConfigFormPage(LlmConfig config, List<LlmConfig> allConfigs,
                             LmStudioModelCatalog.Catalog lmStudio) {
        this.config = config;
        this.allConfigs = allConfigs;
        this.lmStudio = lmStudio;
    }

    @Override
    public UiPage render() {
        String url = config == null
                ? "/admin/llm-configs/new"
                : "/admin/llm-configs/" + config.id().value() + "/edit";
        return UiPage.of(url, new LlmConfigFormComponent(config, allConfigs, lmStudio).render());
    }
}
