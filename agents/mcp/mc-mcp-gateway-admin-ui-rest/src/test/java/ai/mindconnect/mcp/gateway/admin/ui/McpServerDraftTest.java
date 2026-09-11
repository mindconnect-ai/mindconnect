package ai.mindconnect.mcp.gateway.admin.ui;

import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.mcp.gateway.McpServerRegistration;
import ai.mindconnect.mcp.gateway.McpTarget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerDraftTest {

    @Test
    void a_registration_becomes_a_draft_with_its_target_as_text() {
        McpServerDraft draft = McpServerDraft.of(new McpServerRegistration(
                McpServerId.of("gmail"), "Gmail", "mail", true, "gmail",
                new McpTarget.Docker("mcp/gmail:latest", List.of(), Map.of(), List.of(), List.of()),
                null));

        assertThat(draft.id()).isEqualTo("gmail");
        assertThat(draft.displayName()).isEqualTo("Gmail");
        assertThat(draft.enabled()).isTrue();
        assertThat(draft.targetJson()).contains("mcp/gmail:latest");
    }

    @Test
    void a_draft_holds_input_a_registration_would_reject() {
        // The point of the type: half-filled input still renders back.
        McpServerDraft draft = new McpServerDraft("", "Half typed", null, true, "", "{ broken");

        assertThat(draft.id()).isEmpty();
        assertThat(draft.displayName()).isEqualTo("Half typed");
        assertThat(draft.targetJson()).isEqualTo("{ broken");
    }

    @Test
    void an_id_from_the_url_can_be_put_back_in() {
        // The edit form shows the id read-only, so it is not submitted.
        assertThat(new McpServerDraft("", "Gmail", null, true, "gmail", "{}").withId("gmail").id())
                .isEqualTo("gmail");
    }
}
