package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.LlmConfigDetailComponent;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiStack;

/** Read-only detail page for one LLM configuration, with its Pricing section underneath. */
public final class LlmConfigDetailPage extends AdminPage {

    private final LlmConfig config;
    private final UiNode pricing;

    public LlmConfigDetailPage(LlmConfig config) {
        this(config, null);
    }

    /** @param pricing the config's Pricing section, or null where the host keeps no prices */
    public LlmConfigDetailPage(LlmConfig config, UiNode pricing) {
        this.config = config;
        this.pricing = pricing;
    }

    @Override
    public UiPage render() {
        UiNode detail = new LlmConfigDetailComponent(config).render();
        String url = "/admin/llm-configs/" + config.id().value();
        if (pricing == null) return UiPage.of(url, detail);
        return UiPage.of(url, UiStack.of("llm-config-page").gap(16).child(detail).child(pricing));
    }
}
