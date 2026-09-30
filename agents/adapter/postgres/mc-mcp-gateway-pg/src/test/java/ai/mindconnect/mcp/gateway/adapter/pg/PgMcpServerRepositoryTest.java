package ai.mindconnect.mcp.gateway.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.jdbc.Jsonb;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.mcp.gateway.McpServerRegistration;
import ai.mindconnect.mcp.gateway.McpTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PgMcpServerRepositoryTest {

    private static final Namespace ACME = new Namespace("acme");
    private static final Namespace OTHER = new Namespace("other");

    private Sql sql;
    private PgMcpServerRepository acme;
    private PgMcpServerRepository other;

    @BeforeEach
    void setUp() {
        sql = TestDb.freshMcpTables();
        acme = new PgMcpServerRepository(sql, ACME).initSchema();
        other = new PgMcpServerRepository(sql, OTHER).initSchema();
    }

    @Test
    void every_kind_of_target_round_trips() {
        McpServerRegistration docker = new McpServerRegistration(McpServerId.of("gmail"), "Gmail", "mail", true,
                "gmail", new McpTarget.Docker("mcp/gmail:latest",
                        List.of(new McpTarget.Mount("~/.gmail-mcp", "/root/.gmail-mcp")),
                        Map.of("KEY", "value"), List.of("--memory=512m"), List.of("serve")),
                Instant.parse("2026-09-23T10:15:30.123Z"));
        McpServerRegistration process = new McpServerRegistration(McpServerId.of("files"), null, null, false,
                "files", new McpTarget.Process(List.of("npx", "-y", "files-mcp"), Map.of("ROOT", "/tmp")), null);
        McpServerRegistration http = new McpServerRegistration(McpServerId.of("remote"), "Remote", null, true,
                "rem", new McpTarget.Http(URI.create("https://mcp.example.com/mcp"),
                        Map.of("Authorization", "Bearer abc")), null);

        acme.save(docker);
        acme.save(process);
        acme.save(http);

        assertThat(acme.findById(McpServerId.of("gmail"))).contains(docker);
        assertThat(acme.findById(McpServerId.of("files"))).contains(process);
        assertThat(acme.findById(McpServerId.of("remote"))).contains(http);
        // In the order of their ids, as the files are listed.
        assertThat(acme.findAll()).containsExactly(process, docker, http);
    }

    @Test
    void a_server_is_found_by_its_name_ignoring_case() {
        acme.save(registration("gmail", "Gmail"));

        assertThat(acme.findByName("GMAIL")).map(McpServerRegistration::id).contains(McpServerId.of("gmail"));
        assertThat(acme.findByName("nope")).isEmpty();
        assertThat(acme.findByName(null)).isEmpty();
    }

    @Test
    void saving_replaces_and_deleting_removes() {
        acme.save(registration("gmail", "Gmail"));
        acme.save(registration("gmail", "Google Mail"));

        assertThat(acme.findAll()).singleElement()
                .extracting(McpServerRegistration::displayName).isEqualTo("Google Mail");

        acme.deleteById(McpServerId.of("gmail"));
        acme.deleteById(McpServerId.of("gmail"));   // a missing one is not an error

        assertThat(acme.findAll()).isEmpty();
    }

    @Test
    void the_version_moves_with_every_change() {
        long empty = acme.version();
        acme.save(registration("gmail", "Gmail"));
        long saved = acme.version();
        acme.save(registration("gmail", "Google Mail"));
        long replaced = acme.version();
        acme.deleteById(McpServerId.of("gmail"));
        long deleted = acme.version();

        assertThat(saved).isNotEqualTo(empty);
        assertThat(replaced).isNotEqualTo(saved);
        assertThat(deleted).isNotEqualTo(replaced);
        assertThat(acme.version()).as("unchanged when nothing changed").isEqualTo(deleted);
    }

    @Test
    void a_namespace_sees_only_its_own_registrations() {
        acme.save(registration("gmail", "Gmail"));
        other.save(registration("gmail", "Other Gmail"));
        long otherVersion = other.version();

        acme.deleteById(McpServerId.of("gmail"));

        assertThat(acme.findAll()).isEmpty();
        assertThat(other.findById(McpServerId.of("gmail"))).map(McpServerRegistration::displayName)
                .contains("Other Gmail");
        assertThat(other.findByName("gmail")).isEmpty();
        assertThat(other.version()).as("another namespace's change is not ours").isEqualTo(otherVersion);
    }

    @Test
    void a_broken_row_is_skipped_and_costs_the_others_nothing() {
        acme.save(registration("gmail", "Gmail"));
        sql.update("INSERT INTO mc_mcp_server (namespace, id, display_name, doc) VALUES (?, ?, ?, ?)",
                ACME.value(), "broken", "Broken", Jsonb.of("{\"id\": \"broken\"}"));

        assertThat(acme.findAll()).extracting(McpServerRegistration::id).containsExactly(McpServerId.of("gmail"));
        assertThat(acme.findById(McpServerId.of("broken"))).isEmpty();
    }

    static McpServerRegistration registration(String id, String displayName) {
        return new McpServerRegistration(McpServerId.of(id), displayName, null, true, id,
                new McpTarget.Process(List.of("run-" + id), Map.of()), null);
    }
}
