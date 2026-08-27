package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.AgentDetailComponent;
import ai.mindconnect.adminui.ui.component.SessionTableComponent;
import ai.mindconnect.adminui.ui.component.ToolTableComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;

/**
 * Agent detail page with the Details / Tools / Sessions tabs. Holds
 * the full-render and the two patch entry points the controller
 * targets after Delete-Session and Delete-Tool — those are the only
 * incremental updates the page supports today.
 *
 * <p>The {@code initialSection} / {@code selectedRowId} let the
 * controller deep-link the user back to a specific tab+row after an
 * add/edit/copy round-trip.
 */
public final class AgentDetailPage extends AdminPage {

    private final AgentDefinition agent;
    private final String userId;
    private final AgentSessionRepository sessionRepository;
    private final String initialSection;
    private final String selectedRowId;

    public AgentDetailPage(AgentDefinition agent, String userId,
                           AgentSessionRepository sessionRepository) {
        this(agent, userId, sessionRepository, null, null);
    }

    public AgentDetailPage(AgentDefinition agent, String userId,
                           AgentSessionRepository sessionRepository,
                           String initialSection, String selectedRowId) {
        this.agent = agent;
        this.userId = userId;
        this.sessionRepository = sessionRepository;
        this.initialSection = initialSection;
        this.selectedRowId = selectedRowId;
    }

    @Override
    public UiPage render() {
        var detail = new AgentDetailComponent(agent, userId, sessionRepository,
                initialSection, selectedRowId);
        return UiPage.of("/admin/agents/" + agent.id(), detail.render());
    }

    /**
     * REPLACE patch for the Sessions table — called after a session
     * delete so the row disappears without re-fetching the whole
     * detail page.
     */
    public UiPatch refreshSessions() {
        var table = new SessionTableComponent(agent, userId, sessionRepository);
        return patch(UiPatch.Operation.replace(table.id(), table.render()));
    }

    /**
     * REPLACE patch for the Tools table — called after a tool delete.
     */
    public UiPatch refreshTools() {
        var table = new ToolTableComponent(agent);
        return patch(UiPatch.Operation.replace(table.id(), table.render()));
    }
}
