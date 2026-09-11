package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.mcp.gateway.McpGatewayException;
import ai.mindconnect.mcp.gateway.McpTarget;
import ai.mindconnect.mcp.proxy.McpHttpEndpoint;
import ai.mindconnect.mcp.proxy.McpStdioSpawn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpTargetEndpointsTest {

    @TempDir
    Path existingHostDir;

    private final McpTargetEndpoints endpoints = new McpTargetEndpoints("podman");

    /** A variable that no environment sets, so the tests do not depend on one. */
    private static final String UNSET = "MC_TEST_VARIABLE_THAT_IS_NEVER_SET";

    @Test
    void a_variable_in_an_env_value_is_refused_by_field_and_name_before_anything_starts() {
        assertThatThrownBy(() -> endpoints.toEndpoint(new McpTarget.Docker(
                "mcp/github", List.of(), Map.of("GITHUB_TOKEN", "${" + UNSET + "}"),
                List.of(), List.of())))
                .isInstanceOf(McpGatewayException.class)
                .hasMessageContaining("GITHUB_TOKEN")   // which field
                .hasMessageContaining(UNSET)            // which variable
                .hasMessageContaining("user's own variables");
    }

    @Test
    void the_environment_of_this_process_is_never_read() {
        // PATH is set everywhere: expanding it would prove the process environment
        // is a source — and a signed-in user could read ${MC_POSTGRES_PASSWORD}
        // the same way, into a header sent to a URL of their choosing.
        assertThatThrownBy(() -> endpoints.toEndpoint(new McpTarget.Docker(
                "mcp/github", List.of(), Map.of("SEEN_PATH", "${PATH}"), List.of(), List.of())))
                .isInstanceOf(McpGatewayException.class)
                .hasMessageContaining("${PATH}");
    }

    @Test
    void a_default_does_not_make_a_variable_acceptable() {
        assertThatThrownBy(() -> endpoints.toEndpoint(new McpTarget.Process(
                List.of("/bin/echo", "hi"), Map.of("TOKEN", "${" + UNSET + ":plain}"))))
                .isInstanceOf(McpGatewayException.class)
                .hasMessageContaining(UNSET);
    }

    @Test
    void a_variable_in_a_header_is_refused_without_repeating_the_value() {
        assertThatThrownBy(() -> endpoints.toEndpoint(new McpTarget.Http(
                URI.create("https://mcp.example.com/mcp"),
                Map.of("Authorization", "Bearer s3cret-part ${" + UNSET + "}"))))
                .isInstanceOf(McpGatewayException.class)
                .hasMessageContaining("Authorization")
                .hasMessageContaining(UNSET)
                .hasMessageNotContaining("s3cret-part");
    }

    @Test
    void a_placeholder_in_a_key_or_the_image_stays_literal() {
        // A registration is data, not a template language. Only values are
        // checked: the image stays literal, and so does a key that happens to
        // look like a placeholder.
        McpStdioSpawn spawn = (McpStdioSpawn) endpoints.toEndpoint(new McpTarget.Docker(
                "mcp/${" + UNSET + "}", List.of(),
                Map.of("${" + UNSET + "}", "value"), List.of(), List.of()));

        assertThat(spawn.args()).contains("mcp/${" + UNSET + "}");
        assertThat(spawn.args()).containsSubsequence("-e", "${" + UNSET + "}=value");
    }

    @Test
    void a_value_without_a_placeholder_is_handed_through_untouched() {
        McpStdioSpawn spawn = (McpStdioSpawn) endpoints.toEndpoint(new McpTarget.Docker(
                "mcp/github", List.of(), Map.of("MODE", "read-only"), List.of(), List.of()));

        assertThat(spawn.args()).containsSubsequence("-e", "MODE=read-only");
    }

    @Test
    void docker_target_becomes_a_run_command_for_the_configured_binary() {
        McpStdioSpawn spawn = (McpStdioSpawn) endpoints.toEndpoint(new McpTarget.Docker(
                "mcp/gmail:latest",
                List.of(new McpTarget.Mount(existingHostDir.toString(), "/root/.gmail-mcp")),
                Map.of("GMAIL_OAUTH_PATH", "/root/.gmail-mcp/keys.json"),
                List.of("--memory=512m"),
                List.of()));

        assertThat(spawn.command()).isEqualTo("podman");
        assertThat(spawn.args()).containsSubsequence("run", "-i", "--rm")
                .containsSubsequence("-v", existingHostDir + ":/root/.gmail-mcp")
                .containsSubsequence("-e", "GMAIL_OAUTH_PATH=/root/.gmail-mcp/keys.json")
                .containsSubsequence("--memory=512m", "mcp/gmail:latest");
    }

    @Test
    void a_leading_tilde_in_a_mount_resolves_to_the_users_home() {
        assertThat(McpTargetEndpoints.expandHome("~/.gmail-mcp"))
                .isEqualTo(Path.of(System.getProperty("user.home"), ".gmail-mcp"));
        assertThat(McpTargetEndpoints.expandHome("/absolute/elsewhere"))
                .isEqualTo(Path.of("/absolute/elsewhere"));
        assertThat(McpTargetEndpoints.expandHome("relative/~/inside"))
                .isEqualTo(Path.of("relative/~/inside"));
    }

    @Test
    void a_mount_that_does_not_exist_is_named_rather_than_left_to_the_container() {
        McpTarget.Docker target = new McpTarget.Docker(
                "mcp/gmail:latest",
                List.of(new McpTarget.Mount(existingHostDir.resolve("absent").toString(), "/in")),
                Map.of(), List.of(), List.of());

        assertThatThrownBy(() -> endpoints.toEndpoint(target))
                .isInstanceOf(McpGatewayException.class)
                .hasMessageContaining("mount source does not exist")
                .hasMessageContaining("mcp/gmail:latest");
    }

    @Test
    void without_a_container_runtime_a_docker_target_says_so() {
        McpTargetEndpoints none = new McpTargetEndpoints(null);

        assertThatThrownBy(() -> none.toEndpoint(
                new McpTarget.Docker("mcp/gmail:latest", List.of(), Map.of(), List.of(), List.of())))
                .isInstanceOf(McpGatewayException.class)
                .hasMessageContaining("no container runtime");
    }

    @Test
    void process_target_splits_into_executable_and_arguments() {
        McpStdioSpawn spawn = (McpStdioSpawn) endpoints.toEndpoint(new McpTarget.Process(
                List.of("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp"),
                Map.of("NODE_ENV", "production")));

        assertThat(spawn.command()).isEqualTo("npx");
        assertThat(spawn.args()).containsExactly("-y", "@modelcontextprotocol/server-filesystem", "/tmp");
        assertThat(spawn.env()).containsEntry("NODE_ENV", "production");
    }

    @Test
    void http_target_becomes_an_http_endpoint_with_its_headers() {
        McpHttpEndpoint endpoint = (McpHttpEndpoint) endpoints.toEndpoint(new McpTarget.Http(
                URI.create("https://mcp.example.com/mcp"),
                Map.of("Authorization", "Bearer t0ken")));

        assertThat(endpoint.url()).isEqualTo(URI.create("https://mcp.example.com/mcp"));
        assertThat(endpoint.origin()).isEqualTo("https://mcp.example.com");
        assertThat(endpoint.headers()).containsEntry("Authorization", "Bearer t0ken");
    }

    @Test
    void an_http_target_needs_tls_unless_it_is_local() {
        assertThatThrownBy(() -> new McpTarget.Http(URI.create("http://mcp.example.com/mcp"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be https");

        // A server on this machine has no wire to eavesdrop on.
        assertThat(new McpTarget.Http(URI.create("http://localhost:3000/mcp"), Map.of()).url().getHost())
                .isEqualTo("localhost");
    }

    @Test
    void an_http_target_without_a_runtime_still_works() {
        // The container binary is irrelevant for a server we do not start.
        McpTargetEndpoints none = new McpTargetEndpoints(null);

        assertThat(none.toEndpoint(new McpTarget.Http(URI.create("https://mcp.example.com/mcp"), Map.of())))
                .isInstanceOf(McpHttpEndpoint.class);
    }

    @Test
    void a_process_target_without_a_command_is_rejected_where_it_is_built() {
        assertThatThrownBy(() -> new McpTarget.Process(List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command required");
    }
}
