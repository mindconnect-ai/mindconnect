package ai.mindconnect.mcp.gateway.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.jdbc.Jsonb;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.mcp.gateway.McpDiscovery;
import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.mcp.gateway.McpTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PgMcpDiscoveryStoreTest {

    private static final McpServerId GMAIL = McpServerId.of("gmail");

    private Sql sql;
    private PgMcpDiscoveryStore acme;
    private PgMcpDiscoveryStore other;

    @BeforeEach
    void setUp() {
        sql = TestDb.freshMcpTables();
        acme = new PgMcpDiscoveryStore(sql, new Namespace("acme")).initSchema();
        other = new PgMcpDiscoveryStore(sql, new Namespace("other")).initSchema();
    }

    @Test
    void a_discovery_round_trips_with_its_schema_in_order() {
        // The schema goes into the LLM request as it is; a shuffled one would
        // change the request from one start to the next.
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("query"));
        schema.put("properties", Map.of("query", Map.of("type", "string")));
        McpDiscovery discovery = new McpDiscovery(Instant.parse("2026-09-23T10:15:30Z"), List.of(
                new McpTool("search_emails", "Searches mail", schema),
                new McpTool("send_email", null, Map.of())));

        acme.save(GMAIL, discovery);

        McpDiscovery read = acme.find(GMAIL).orElseThrow();
        assertThat(read).isEqualTo(discovery);
        assertThat(read.tools().get(0).inputSchema().keySet()).containsExactly("type", "required", "properties");
    }

    @Test
    void saving_again_replaces_the_answer() {
        acme.save(GMAIL, new McpDiscovery(Instant.parse("2026-09-01T00:00:00Z"),
                List.of(new McpTool("old", null, Map.of()))));
        acme.save(GMAIL, new McpDiscovery(Instant.parse("2026-09-02T00:00:00Z"),
                List.of(new McpTool("new", null, Map.of()))));

        assertThat(acme.find(GMAIL).orElseThrow().tools()).extracting(McpTool::name).containsExactly("new");
    }

    @Test
    void deleting_says_whether_there_was_an_entry() {
        acme.save(GMAIL, new McpDiscovery(Instant.now(), List.of()));

        assertThat(acme.delete(GMAIL)).isTrue();
        assertThat(acme.delete(GMAIL)).isFalse();
        assertThat(acme.find(GMAIL)).isEmpty();
    }

    @Test
    void a_namespace_sees_only_its_own_cache() {
        acme.save(GMAIL, new McpDiscovery(Instant.now(), List.of(new McpTool("search_emails", null, Map.of()))));

        assertThat(other.find(GMAIL)).isEmpty();
        assertThat(other.delete(GMAIL)).isFalse();
        assertThat(acme.find(GMAIL)).isPresent();
    }

    @Test
    void an_unreadable_row_is_a_missing_one() {
        sql.update("INSERT INTO mc_mcp_schema_cache (namespace, server_id, doc) VALUES (?, ?, ?)",
                "acme", "gmail", Jsonb.of("{\"serverId\": \"gmail\", \"fetchedAt\": \"yesterday\"}"));

        assertThat(acme.find(GMAIL)).isEmpty();
    }
}
