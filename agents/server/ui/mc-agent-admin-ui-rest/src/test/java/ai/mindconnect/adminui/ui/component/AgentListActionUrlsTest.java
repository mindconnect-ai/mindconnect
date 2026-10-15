package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentDefinitionStatus;
import ai.mindconnect.agent.AgentId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent list names its actions by controller method rather than by URL
 * string. This pins down that the derivation produces exactly the routes the
 * hand-written strings used to — the migration must be invisible to the client.
 *
 * <p>It is also the regression net the strings never had: change a handler's
 * {@code @PostMapping} path and this test fails, instead of the button.
 */
class AgentListActionUrlsTest {

    private static final AgentId AGENT_ID = AgentId.of("11111111-2222-3333-4444-555555555555");

    private static AgentDefinition agent() {
        return new AgentDefinition(AGENT_ID, "Scout", "A test agent",
                "assistants", "bot", "prompt", null, "cfg", 5, null,
                AgentDefinitionStatus.ACTIVE, List.of(), List.of(), null, null, null, null);
    }

    /** The whole rendered tree as JSON — triggers included, wherever they sit. */
    private static String renderedJson() throws Exception {
        return new ObjectMapper()
                .writeValueAsString(new AgentListComponent(List.of(agent()), null).render());
    }

    @Test
    void everyActionKeepsTheRouteItHadBefore() throws Exception {
        String json = renderedJson();

        assertThat(json).contains("\"url\":\"/admin/api/agents/search\"");
        assertThat(json).contains("\"url\":\"/admin/api/agents/new\"");
        assertThat(json).contains("\"url\":\"/chat/api/agents/" + AGENT_ID.value() + "/sessions\"");
        assertThat(json).contains("\"url\":\"/admin/api/agents/" + AGENT_ID.value() + "/copy\"");
        assertThat(json).contains("\"url\":\"/admin/api/agents/" + AGENT_ID.value() + "\"");
    }

    @Test
    void theVerbComesFromTheHandlersOwnMapping() throws Exception {
        String json = renderedJson();

        // delete is the interesting one: a DELETE derived from @DeleteMapping,
        // not from a string someone remembered to type in upper case.
        assertThat(json).contains("\"method\":\"DELETE\"");
        assertThat(json).contains("\"method\":\"POST\"");
        assertThat(json).contains("\"method\":\"GET\"");
    }
}
