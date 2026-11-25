package ai.mindconnect.mcp.gateway.admin.ui;

import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.mcp.gateway.McpTarget;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpTargetFormTest {

    private final McpTargetForm form = new McpTargetForm(new JsonMapperFactory(List.of()));

    @Test
    void a_target_survives_the_round_trip_through_the_form() {
        McpTarget.Docker original = new McpTarget.Docker(
                "mcp/gmail:latest",
                List.of(new McpTarget.Mount("~/.gmail-mcp", "/root/.gmail-mcp")),
                Map.of("GMAIL_OAUTH_PATH", "/root/.gmail-mcp/keys.json"),
                List.of("--memory=512m"),
                List.of());

        assertThat(form.fromJson(form.toJson(original))).isEqualTo(original);
    }

    @Test
    void an_http_target_survives_it_too() {
        McpTarget.Http original = new McpTarget.Http(
                URI.create("https://mcp.example.com/mcp"), Map.of("Authorization", "Bearer t0ken"));

        assertThat(form.fromJson(form.toJson(original))).isEqualTo(original);
    }

    @Test
    void the_messages_say_what_to_fix() {
        assertThatThrownBy(() -> form.fromJson("not json"))
                .hasMessageContaining("not valid JSON");
        assertThatThrownBy(() -> form.fromJson("{}"))
                .hasMessageContaining("needs a \"type\"");
        assertThatThrownBy(() -> form.fromJson("{\"type\":\"ftp\"}"))
                .hasMessageContaining("Unsupported target type");
        assertThatThrownBy(() -> form.fromJson("{\"type\":\"docker\"}"))
                .hasMessageContaining("missing \"image\"");
        assertThatThrownBy(() -> form.fromJson("{\"type\":\"process\",\"command\":[]}"))
                .hasMessageContaining("non-empty \"command\"");
        assertThatThrownBy(() -> form.fromJson("{\"type\":\"http\",\"url\":\"http://example.com/mcp\"}"))
                .hasMessageContaining("must be https");
    }

    @Test
    void an_empty_form_starts_from_a_skeleton_rather_than_nothing() {
        assertThat(form.toJson(null)).contains("\"type\"").contains("docker");
    }
}
