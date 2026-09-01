package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.AgentListComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import java.util.List;
import ai.mindconnect.ui.model.UiPage;

/**
 * Top-level agent list — what the operator sees at {@code /admin/agents}.
 * Wraps an {@link AgentListComponent}; no incremental patches today.
 */
public final class AgentListPage extends AdminPage {

    private final List<AgentDefinition> agents;
    private final String query;

    public AgentListPage(List<AgentDefinition> agents) {
        this(agents, null);
    }

    public AgentListPage(List<AgentDefinition> agents, String query) {
        this.agents = agents;
        this.query = query;
    }

    @Override
    public UiPage render() {
        return UiPage.of("/admin/agents", new AgentListComponent(agents, query).render());
    }
}
