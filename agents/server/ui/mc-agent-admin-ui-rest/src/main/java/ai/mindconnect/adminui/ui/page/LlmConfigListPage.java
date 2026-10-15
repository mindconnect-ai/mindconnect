package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.LlmConfigListComponent;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.ui.model.UiPage;

import java.util.List;

/** List of all LLM configurations at {@code /admin/llm-configs}. */
public final class LlmConfigListPage extends AdminPage {

    private final List<LlmConfig> configs;

    public LlmConfigListPage(List<LlmConfig> configs) {
        this.configs = configs;
    }

    @Override
    public UiPage render() {
        return UiPage.of("/admin/llm-configs",
                new LlmConfigListComponent(configs).render());
    }
}
