package ai.mindconnect.agentrest.dto;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AgentToolId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** PUT /api/agents/{id}/tools takes the tool list in the shape GET /api/agents/{id} returns it. */
class UpdateToolsRequestTest {

    @Test
    void theToolsOfAnAgentAsReadComeBackAsTools() throws Exception {
        String body = """
                {"tools": [{"id": "00000003-0000-0000-0000-000000000020", "name": "web_search",
                            "description": "Searches the web using Tavily.", "overrides": {},
                            "enabled": true, "deferred": false, "needsApproval": true, "maxResultChars": null},
                           {"name": "fetch_tool_result", "enabled": true}]}
                """;

        UpdateToolsRequest request = new ObjectMapper().readValue(body, UpdateToolsRequest.class);
        AgentTool search = request.tools().get(0).toTool();
        AgentTool added = request.tools().get(1).toTool();

        assertThat(search.id()).isEqualTo(AgentToolId.of("00000003-0000-0000-0000-000000000020"));
        assertThat(search.needsApproval()).isTrue();
        assertThat(added.name()).isEqualTo("fetch_tool_result");
    }
}
