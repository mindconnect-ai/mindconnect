package ai.mindconnect.agent.runtime.service.approval;

import ai.mindconnect.agent.SessionId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** GET /api/sessions/{id}/approvals serializes these: the session ids must come out as bare values. */
class ToolApprovalJsonTest {

    @Test
    void sessionIdsAreBareValuesOnTheWire() throws Exception {
        SessionId origin = SessionId.of("11111111-1111-1111-1111-111111111111");
        SessionId root = SessionId.of("22222222-2222-2222-2222-222222222222");
        ToolApproval approval = new ToolApproval("req", "call_AbC", "web_search", "{}",
                origin, root, "task_tool_x", Instant.EPOCH);

        JsonNode json = new ObjectMapper().registerModule(new JavaTimeModule()).valueToTree(approval);

        assertThat(json.get("originSessionId").isTextual()).isTrue();
        assertThat(json.get("originSessionId").asText()).isEqualTo(origin.value());
        assertThat(json.get("rootSessionId").asText()).isEqualTo(root.value());
        assertThat(json.get("callId").asText()).isEqualTo("call_AbC");
    }
}
