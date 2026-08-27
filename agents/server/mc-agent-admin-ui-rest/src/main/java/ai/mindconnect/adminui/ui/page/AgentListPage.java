package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.AgentListComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.common.Page;
import ai.mindconnect.ui.model.UiPage;

/**
 * Top-level agent list — what the operator sees at {@code /admin/agents}.
 * Wraps an {@link AgentListComponent}; no incremental patches today.
 */
public final class AgentListPage extends AdminPage {

    private final Page<AgentDefinition> page;
    private final String query;

    public AgentListPage(Page<AgentDefinition> page) {
        this(page, null);
    }

    public AgentListPage(Page<AgentDefinition> page, String query) {
        this.page = page;
        this.query = query;
    }

    @Override
    public UiPage render() {
        return UiPage.of("/admin/agents", new AgentListComponent(page, query).render());
    }
}
