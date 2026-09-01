package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentDefinitionStatus;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.common.Namespace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    private static final UUID AGENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static AgentDefinition agent() {
        return new AgentDefinition(AGENT_ID, new Namespace("local"), "Scout", "A test agent",
                "assistants", "bot", "prompt", null, "cfg", 5, null,
                AgentDefinitionStatus.ACTIVE, List.of(), List.of(), null, null, null, null);
    }

    /** Renders without a database: the detail header only asks for the session list. */
    private static final AgentSessionRepository NO_SESSIONS = new AgentSessionRepository() {
        @Override public AgentSession save(AgentSession session) { throw new UnsupportedOperationException(); }
        @Override public Optional<AgentSession> findById(UUID id) { return Optional.empty(); }
        @Override public List<AgentSession> findByAgentDefinitionId(UUID id, Namespace ns, String userId) { return List.of(); }
        @Override public List<AgentSession> findByUser(Namespace ns, String userId) { return List.of(); }
        @Override public List<AgentSession> findByParentSessionId(UUID parentSessionId) { return List.of(); }
        @Override public void deleteById(UUID id) { throw new UnsupportedOperationException(); }
    };

    private static String json(Object node) throws Exception {
        return new ObjectMapper().writeValueAsString(node);
    }

    @Test
    void theDetailHeaderKeepsItsRoutes() throws Exception {
        String out = json(new AgentDetailComponent(agent(), "u", NO_SESSIONS).render());

        assertThat(out).contains("\"url\":\"/admin/api/agents/" + AGENT_ID + "/edit\"");
        assertThat(out).contains("\"url\":\"/admin/api/agents/" + AGENT_ID + "\"");
        assertThat(out).contains("\"method\":\"DELETE\"");
    }

    @Test
    void theToolTableKeepsItsRoutes() throws Exception {
        String out = json(new ToolTableComponent(agent()).render());

        assertThat(out).contains("\"url\":\"/admin/api/agents/" + AGENT_ID + "/tools/new\"");
    }

    @Test
    void aRowActionRendersThePlaceholderTheClientFillsIn() throws Exception {
        String out = json(new ToolTableComponent(agent()).render());

        assertThat(out).contains("\"url\":\"/admin/api/agents/" + AGENT_ID + "/tools/{id}\"");
        assertThat(out).contains("\"url\":\"/admin/api/agents/" + AGENT_ID + "/tools/{id}/edit\"");
        // The braces must survive the URI builder unencoded, and the sentinel
        // must not leak into what the client sees.
        assertThat(out).doesNotContain("%7Bid%7D");
        assertThat(out).doesNotContain("0000000000ff");
    }
}
