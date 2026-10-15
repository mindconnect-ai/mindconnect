package ai.mindconnect.mcp.gateway.adapter.pg;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.mcp.gateway.McpDiscovery;
import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.mcp.gateway.local.McpDiscoveryJson;
import ai.mindconnect.mcp.gateway.local.McpDiscoveryStore;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link McpDiscoveryStore} on Postgres: one row of {@code mc_mcp_schema_cache}
 * per server, keyed by {@code (namespace, server_id)}. The document is what a
 * cache file holds ({@link McpDiscoveryJson}) plus the server's id.
 *
 * <p>A cache, so a row that cannot be read or written costs a rediscovery and
 * is logged, never thrown — as the files do. Nothing is imported from the
 * files: an empty cache only means each server is asked once more.
 */
public final class PgMcpDiscoveryStore implements McpDiscoveryStore {

    private static final Logger log = LoggerFactory.getLogger(PgMcpDiscoveryStore.class);

    private final ScopeSupplier scope;
    private final DocumentTable<ObjectNode> discoveries;

    public PgMcpDiscoveryStore(DataSource dataSource, ScopeSupplier scope) {
        this(Sql.of(dataSource), scope);
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgMcpDiscoveryStore(Sql sql, ScopeSupplier scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.discoveries = DocumentTable.of(ObjectNode.class)
                .table("mc_mcp_schema_cache")
                .partitionKey("namespace", "TEXT", d -> scope.namespace().value())
                .id("server_id", "TEXT", d -> d.get("serverId").asText())
                .build(sql);
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public PgMcpDiscoveryStore initSchema() {
        discoveries.createSchema();
        return this;
    }

    @Override
    public Optional<McpDiscovery> find(McpServerId server) {
        try {
            return discoveries.findById(scope.namespace().value(), server.value()).map(McpDiscoveryJson::read);
        } catch (RuntimeException e) {
            log.warn("MCP discovery cache for '{}' in namespace '{}' unreadable: {}",
                    server, scope.namespace().value(), e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void save(McpServerId server, McpDiscovery discovery) {
        ObjectNode document = McpDiscoveryJson.write(discovery, JsonNodeFactory.instance);
        document.put("serverId", server.value());
        try {
            discoveries.save(document);
        } catch (RuntimeException e) {
            // A cache that cannot be written is a slow start-up, not a failure.
            log.warn("cannot write MCP discovery cache for '{}' in namespace '{}': {}",
                    server, scope.namespace().value(), e.toString());
        }
    }

    @Override
    public boolean delete(McpServerId server) {
        try {
            return discoveries.deleteById(scope.namespace().value(), server.value());
        } catch (RuntimeException e) {
            log.warn("cannot drop MCP discovery cache for '{}' in namespace '{}': {}",
                    server, scope.namespace().value(), e.toString());
            return false;
        }
    }
}
