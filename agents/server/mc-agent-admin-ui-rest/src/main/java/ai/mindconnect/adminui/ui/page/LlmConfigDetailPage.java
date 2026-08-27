package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.LlmConfigDetailComponent;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.ui.model.UiPage;

/** Read-only detail page for one LLM configuration. */
public final class LlmConfigDetailPage extends AdminPage {

    private final LlmConfig config;

    public LlmConfigDetailPage(LlmConfig config) {
        this.config = config;
    }

    @Override
    public UiPage render() {
        return UiPage.of("/admin/llm-configs/" + config.id(),
                new LlmConfigDetailComponent(config).render());
    }
}
