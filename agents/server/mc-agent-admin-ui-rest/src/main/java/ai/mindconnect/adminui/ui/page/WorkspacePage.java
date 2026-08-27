package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.WorkspaceScopeListComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.tools.workspace.WorkspaceScope;
import ai.mindconnect.agent.tools.workspace.WorkspaceStore;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiSection;

/**
 * Workspace browser page — three scopes (SESSION / AGENT_USER / USER)
 * each rendered by a {@link WorkspaceScopeListComponent}. The session
 * scope's list also carries the "Back to Conversation" link in its
 * header.
 *
 * <p>Static page: no incremental patches. The whole page re-renders
 * when the operator navigates back to it.
 */
public final class WorkspacePage extends AdminPage {

    private final AgentSession session;
    private final AgentDefinition agent;

    private final WorkspaceScopeListComponent sessionList;
    private final WorkspaceScopeListComponent agentList;
    private final WorkspaceScopeListComponent userList;

    public WorkspacePage(AgentSession session, AgentDefinition agent, WorkspaceStore store) {
        this.session = session;
        this.agent = agent;

        var sessionScope = WorkspaceScope.session(agent.id(), session.userId(), session.id());
        var agentScope   = WorkspaceScope.agentUser(agent.id(), session.userId());
        var userScope    = WorkspaceScope.user(session.userId());

        this.sessionList = new WorkspaceScopeListComponent(session.id(), "session",
                "Session workspace",
                "Scratch space for this session only — deleted with the session.",
                store, sessionScope);
        this.agentList = new WorkspaceScopeListComponent(session.id(), "agent",
                "Agent persistent memory",
                "Survives across sessions with this user + agent (e.g. notes.md).",
                store, agentScope);
        this.userList = new WorkspaceScopeListComponent(session.id(), "user",
                "User profile (shared across agents)",
                "Cross-agent profile, read-only for most agents.",
                store, userScope);
    }

    @Override
    public UiPage render() {
        String sessionId = session.id().toString();

        UiList sessionListUi = sessionList.render();
        UiList agentListUi   = agentList.render();
        UiList userListUi    = userList.render();

        // Back link belongs on the first (visible) list — three stacked
        // sections show all three at once, so anchoring it on the session
        // list keeps it at the top of the visible content.

        var stacked = UiSection.of("workspace-page-" + sessionId, null)
                .section("ws-session", "Session", sessionListUi)
                .section("ws-agent",   "Agent",   agentListUi)
                .section("ws-user",    "User",    userListUi);

        var outer = UiSection.of("workspace-session-" + sessionId,
                        "Workspace — " + agent.name())
                .section("workspace", "Workspace", stacked);

        return UiPage.of("/admin/sessions/" + sessionId + "/workspace", outer);
    }
}
