package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.mcp.gateway.McpServerRegistration;
import ai.mindconnect.mcp.gateway.McpTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileMcpServerRepositoryTest {

    private static final Namespace ACME = new Namespace("acme");

    @TempDir
    Path base;

    @Test
    void reads_a_docker_registration() throws IOException {
        write("gmail.json", """
                {
                  "id": "gmail",
                  "displayName": "Gmail",
                  "toolNamePrefix": "gmail",
                  "target": {
                    "type": "docker",
                    "image": "mcp/gmail:latest",
                    "mounts": [ { "hostPath": "~/.gmail-mcp", "containerPath": "/root/.gmail-mcp" } ],
                    "env": { "GMAIL_OAUTH_PATH": "/root/.gmail-mcp/keys.json" }
                  }
                }
                """);

        assertThat(repository().findAll()).singleElement().satisfies(registration -> {
            assertThat(registration.id()).isEqualTo(McpServerId.of("gmail"));
            assertThat(registration.displayName()).isEqualTo("Gmail");
            assertThat(registration.enabled()).isTrue();   // default
            assertThat(registration.target()).isInstanceOfSatisfying(McpTarget.Docker.class, docker -> {
                assertThat(docker.image()).isEqualTo("mcp/gmail:latest");
                assertThat(docker.mounts()).containsExactly(
                        new McpTarget.Mount("~/.gmail-mcp", "/root/.gmail-mcp"));
                assertThat(docker.env()).containsEntry("GMAIL_OAUTH_PATH", "/root/.gmail-mcp/keys.json");
            });
        });
    }

    @Test
    void registrations_live_in_the_namespaces_system_folder() {
        // Where every configuration store keeps its documents: agents and LLM
        // configs sit in the same <namespace>/system directory.
        repository().save(new McpServerRegistration(McpServerId.of("remote"), "Remote", null, true, "rem",
                new McpTarget.Http(URI.create("https://mcp.example.com/mcp"),
                        Map.of("Authorization", "Bearer ${MCP_TOKEN}")),
                null));

        assertThat(base.resolve("acme").resolve("system").resolve("mcp-servers").resolve("remote.json")).exists();
        assertThat(repository().findAll()).singleElement().satisfies(registration ->
                assertThat(registration.target()).isInstanceOfSatisfying(McpTarget.Http.class, http ->
                        assertThat(http.headers()).containsEntry("Authorization", "Bearer ${MCP_TOKEN}")));
    }

    @Test
    void a_store_bound_to_another_namespace_sees_nothing_of_this_one() throws IOException {
        write("gmail.json", """
                { "id": "gmail", "target": { "type": "process", "command": ["a"] } }
                """);

        assertThat(new FileMcpServerRepository(base, new Namespace("other")).findAll()).isEmpty();
    }

    @Test
    void a_version_moves_when_a_registration_is_added() throws IOException {
        FileMcpServerRepository repository = repository();
        long before = repository.version();

        write("two.json", """
                { "id": "two", "target": { "type": "process", "command": ["b"] } }
                """);

        assertThat(repository.version()).isNotEqualTo(before);
    }

    @Test
    void deleting_removes_only_the_named_server() throws IOException {
        write("gone.json", """
                { "id": "gone", "target": { "type": "process", "command": ["a"] } }
                """);
        write("stays.json", """
                { "id": "stays", "target": { "type": "process", "command": ["b"] } }
                """);

        repository().deleteById(McpServerId.of("gone"));

        assertThat(repository().findAll()).extracting(r -> r.id().value()).containsExactly("stays");
    }

    @Test
    void one_broken_file_does_not_cost_the_others() throws IOException {
        write("broken.json", "{ \"id\": \"broken\" }");                 // no target
        write("unsupported.json", """
                { "id": "remote", "target": { "type": "websocket", "url": "wss://example.invalid/mcp" } }
                """);
        write("good.json", """
                { "id": "good", "target": { "type": "process", "command": ["mcp-good"] } }
                """);

        assertThat(repository().findAll()).extracting(r -> r.id().value()).containsExactly("good");
    }

    @Test
    void tool_name_prefix_defaults_to_the_id() throws IOException {
        write("files.json", """
                { "id": "files", "target": { "type": "process", "command": ["mcp-files"] } }
                """);

        assertThat(repository().findAll()).singleElement()
                .extracting(McpServerRegistration::toolNamePrefix).isEqualTo("files");
    }

    @Test
    void a_server_is_found_by_its_display_name_ignoring_case() throws IOException {
        write("gh.json", """
                { "id": "gh", "displayName": "GitHub", "target": { "type": "process", "command": ["a"] } }
                """);

        assertThat(repository().findByName("github")).get()
                .extracting(r -> r.id().value()).isEqualTo("gh");
        assertThat(repository().findByName("gitlab")).isEmpty();
    }

    @Test
    void a_hand_dropped_file_whose_id_is_not_one_is_skipped() throws IOException {
        // Upper case is not an id. Such a file could never be saved or deleted
        // again, so it is left out with a warning rather than served half-usable.
        write("Upper.json", """
                { "id": "Upper", "target": { "type": "process", "command": ["a"] } }
                """);
        write("fine.json", """
                { "id": "fine", "target": { "type": "process", "command": ["b"] } }
                """);

        assertThat(repository().findAll()).extracting(r -> r.id().value()).containsExactly("fine");
    }

    @Test
    void an_empty_store_is_no_registrations_rather_than_a_failure() {
        assertThat(repository().findAll()).isEmpty();
        assertThat(repository().version()).isZero();
    }

    private FileMcpServerRepository repository() {
        return new FileMcpServerRepository(base, ACME);
    }

    private void write(String name, String json) throws IOException {
        Path dir = base.resolve(ACME.value()).resolve("system").resolve("mcp-servers");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), json);
    }
}
