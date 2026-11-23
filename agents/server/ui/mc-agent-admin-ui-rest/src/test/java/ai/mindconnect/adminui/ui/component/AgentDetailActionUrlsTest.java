package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent detail page and its tool table, pinned to the routes their URL
 * strings used to produce.
 *
 * <p>The row actions are the interesting half: a table row supplies its own
 * id, so the URL has to keep the literal {@code {id}} the client substitutes.
 * That is what {@code UiActions.ROW_ID} is for, and getting it wrong would
 * ship a percent-encoded {@code %7Bid%7D} that silently matches nothing.
 */
class AgentDetailActionUrlsTest {

    private static final AgentId AGENT_ID = AgentId.of("11111111-2222-3333-4444-555555555555");

    private static ManagedAgent agent() {
        return new ManagedAgent(ai.mindconnect.agent.management.api.AgentId.of(AGENT_ID.value()),
                new ai.mindconnect.agent.runtime.api.domain.AgentDefinition("Scout", "A test agent", "prompt",
                        ai.mindconnect.agent.runtime.api.domain.ModelSettings.ofModel("cfg"), List.of(), 5,
                        java.util.Map.of()),
                "assistants", "bot", null, ManagedAgent.Status.ACTIVE, null, null, 0);
    }

    /** Renders without a runtime: the detail header only asks for the session list. */
    private static final SessionManagement NO_SESSIONS = (SessionManagement) java.lang.reflect.Proxy.newProxyInstance(
            SessionManagement.class.getClassLoader(), new Class<?>[] {SessionManagement.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("list")) {
                    return List.of();
                }
                throw new UnsupportedOperationException(method.getName());
            });

    private static String json(Object node) throws Exception {
        return new ObjectMapper().writeValueAsString(node);
    }

    @Test
    void theDetailHeaderKeepsItsRoutes() throws Exception {
        String out = json(new AgentDetailComponent(agent(), "u", NO_SESSIONS).render());

        assertThat(out).contains("\"url\":\"/admin/api/agents/" + AGENT_ID.value() + "/edit\"");
        assertThat(out).contains("\"url\":\"/admin/api/agents/" + AGENT_ID.value() + "\"");
        assertThat(out).contains("\"method\":\"DELETE\"");
    }

    @Test
    void theToolTableKeepsItsRoutes() throws Exception {
        String out = json(new ToolTableComponent(agent()).render());

        assertThat(out).contains("\"url\":\"/admin/api/agents/" + AGENT_ID.value() + "/tools/new\"");
    }

    @Test
    void aRowActionRendersThePlaceholderTheClientFillsIn() throws Exception {
        String out = json(new ToolTableComponent(agent()).render());

        assertThat(out).contains("\"url\":\"/admin/api/agents/" + AGENT_ID.value() + "/tools/{id}\"");
        assertThat(out).contains("\"url\":\"/admin/api/agents/" + AGENT_ID.value() + "/tools/{id}/edit\"");
        // The braces must survive the URI builder unencoded, and the sentinel
        // must not leak into what the client sees.
        assertThat(out).doesNotContain("%7Bid%7D");
        assertThat(out).doesNotContain("0000000000ff");
    }

    /**
     * The session table spans two controllers: opening a session is a chat-UI
     * route, deleting it an admin one. The strings gave no hint of that; the
     * method names do.
     */
    @Test
    void theSessionTableReachesBothControllers() throws Exception {
        String out = json(new SessionTableComponent(AGENT_ID, "u", NO_SESSIONS).render());

        assertThat(out).contains("\"url\":\"/chat/api/agents/" + AGENT_ID.value() + "/sessions\"");
        assertThat(out).contains("\"url\":\"/chat/api/sessions/{id}\"");
        assertThat(out).contains("\"url\":\"/admin/api/agents/" + AGENT_ID.value() + "/sessions/{id}\"");
        assertThat(out).doesNotContain("%7Bid%7D");
    }
}
