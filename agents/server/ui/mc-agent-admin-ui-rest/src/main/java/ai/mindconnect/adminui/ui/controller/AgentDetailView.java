package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.component.AgentSetting;
import ai.mindconnect.adminui.ui.page.AgentDetailPage;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Builds an agent's detail screen for the signed-in user. Saving the agent
 * and changing its tools both answer with it, so the two controllers share
 * this one place that gathers what it shows.
 */
@Component
class AgentDetailView {

    private final AgentManagement agents;
    private final SessionManagement sessions;
    /** The "Memory" tab — what the agent keeps about its users; optional like the memory itself. */
    private final ObjectProvider<MemoryAdminUiController> memoryUi;

    AgentDetailView(AgentRuntime runtime, ObjectProvider<MemoryAdminUiController> memoryUi) {
        this.agents = runtime.service(AgentManagement.class);
        this.sessions = runtime.service(SessionManagement.class);
        this.memoryUi = memoryUi;
    }

    /** The screen of the agent with this id, opened on a section and a row of it; not found for no such agent. */
    ResponseEntity<UiPage> page(String agentId, String section, String row, OidcUser user) {
        return agents.find(AgentId.of(agentId))
                .map(agent -> ResponseEntity.ok(new AgentDetailPage(agent, user.getPreferredUsername(), sessions,
                        section, row).memoryTab(memoryTab(agent)).render()))
                .orElse(ResponseEntity.notFound().build());
    }

    /** The agent's screen for the parts that are patched in place: its tools, its sessions. */
    AgentDetailPage of(ManagedAgent agent, OidcUser user) {
        return new AgentDetailPage(agent, user.getPreferredUsername(), sessions);
    }

    private UiNode memoryTab(ManagedAgent agent) {
        MemoryAdminUiController memory = memoryUi.getIfAvailable();
        return memory == null ? null : memory.agentTab(agent.id(), AgentSetting.tools(agent)).orElse(null);
    }
}
