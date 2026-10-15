package ai.mindconnect.mcp.gateway.adapter.pg;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.mcp.gateway.McpServerRegistration;
import ai.mindconnect.mcp.gateway.local.McpRegistrationJson;
import ai.mindconnect.mcp.gateway.local.McpServerRepository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link McpServerRepository} on Postgres: one row of {@code mc_mcp_server}
 * per registration, keyed by {@code (namespace, id)}, the display name beside
 * the document because a person looks a server up by it. The document is the
 * JSON a registration file holds ({@link McpRegistrationJson}) — the
 * registration's target is a sealed type the application's mapper does not
 * know, and one shape for both stores keeps an import a copy.
 *
 * <p>Every row it writes carries the namespace the scope names for the
 * current call, and every statement it runs matches it.
 *
 * <p>{@link #importOnce} brings over the registration files a namespace had
 * before it moved to Postgres — once, remembered in {@code mc_mcp_server_import},
 * so that deleting every registration afterwards does not bring the files back
 * on the next start.
 */
public final class PgMcpServerRepository implements McpServerRepository {

    private static final Logger log = LoggerFactory.getLogger(PgMcpServerRepository.class);

    private static final String IMPORT_TABLE = "mc_mcp_server_import";

    private final Sql sql;
    private final ScopeSupplier scope;
    private final DocumentTable<ObjectNode> servers;

    public PgMcpServerRepository(DataSource dataSource, ScopeSupplier scope) {
        this(Sql.of(dataSource), scope);
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgMcpServerRepository(Sql sql, ScopeSupplier scope) {
        this.sql = Objects.requireNonNull(sql, "sql");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.servers = table(sql, scope);
    }

    private static DocumentTable<ObjectNode> table(Sql sql, ScopeSupplier scope) {
        return DocumentTable.of(ObjectNode.class)
                .table("mc_mcp_server")
                .partitionKey("namespace", "TEXT", d -> scope.namespace().value())
                .id("id", "TEXT", d -> d.get("id").asText())
                .requiredColumn("display_name", "TEXT", d -> d.get("displayName").asText())
                .build(sql);
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}) of both tables. */
    public PgMcpServerRepository initSchema() {
        servers.createSchema();
        sql.execute("CREATE TABLE IF NOT EXISTS " + IMPORT_TABLE + " (\n"
                + "    namespace TEXT PRIMARY KEY,\n"
                + "    imported INTEGER NOT NULL DEFAULT 0,\n"
                + "    imported_at TIMESTAMPTZ NOT NULL DEFAULT now()\n"
                + ")");
        return this;
    }

    @Override
    public Optional<McpServerRegistration> findById(McpServerId id) {
        return servers.findById(scope.namespace().value(), id.value()).flatMap(this::read);
    }

    @Override
    public List<McpServerRegistration> findAll() {
        return read(servers.find("WHERE namespace = ? ORDER BY id", scope.namespace().value()));
    }

    @Override
    public Optional<McpServerRegistration> findByName(String name) {
        if (name == null) return Optional.empty();
        return read(servers.find("WHERE namespace = ? AND lower(display_name) = lower(?) ORDER BY id",
                scope.namespace().value(), name)).stream().findFirst();
    }

    @Override
    public void save(McpServerRegistration registration) {
        servers.save(McpRegistrationJson.write(registration, JsonNodeFactory.instance));
        log.info("MCP registration '{}' saved in namespace '{}'", registration.id(), scope.namespace().value());
    }

    @Override
    public void deleteById(McpServerId id) {
        if (servers.deleteById(scope.namespace().value(), id.value())) {
            log.info("MCP registration '{}' deleted from namespace '{}'", id, scope.namespace().value());
        }
    }

    /**
     * Changes when a row of this namespace is added, removed or written: the
     * number of rows and the latest {@code updated_at}. Coarse on purpose — it
     * only has to differ, and it is asked on every tool lookup, so it is one
     * aggregate over the primary key's prefix.
     */
    @Override
    public long version() {
        return sql.queryOne("SELECT count(*) AS n, max(updated_at) AS t FROM " + servers.table()
                        + " WHERE namespace = ?",
                row -> {
                    Instant latest = row.instant("t");
                    long micros = latest == null ? 0L : latest.getEpochSecond() * 1_000_000L + latest.getNano() / 1_000;
                    return 31 * micros + row.longValue("n");
                },
                scope.namespace().value()).orElse(0L);
    }

    /**
     * Imports what {@code files} holds, the first time this namespace is seen
     * here — and only when it has no registration here yet. The files are read,
     * never changed or deleted. Claiming the namespace in {@code mc_mcp_server_import}
     * and inserting its rows happen in one transaction, so a second process
     * starting at the same moment waits for the first and then imports nothing;
     * and an import that fails leaves no claim behind, so the next start tries
     * again.
     *
     * @return how many registrations were imported
     */
    public int importOnce(McpServerRepository files) {
        return sql.inTransaction(tx -> {
            int claimed = tx.update("INSERT INTO " + IMPORT_TABLE + " (namespace) VALUES (?) ON CONFLICT DO NOTHING",
                    scope.namespace().value());
            if (claimed == 0) {
                return 0;
            }
            DocumentTable<ObjectNode> rows = table(tx, scope);
            if (rows.exists("WHERE namespace = ?", scope.namespace().value())) {
                log.info("MCP registrations of namespace '{}' are in Postgres already — no files imported",
                        scope.namespace().value());
                return 0;
            }
            int imported = 0;
            for (McpServerRegistration registration : files.findAll()) {
                if (rows.insert(McpRegistrationJson.write(registration, JsonNodeFactory.instance))) {
                    imported++;
                }
            }
            tx.update("UPDATE " + IMPORT_TABLE + " SET imported = ? WHERE namespace = ?", imported, scope.namespace().value());
            if (imported > 0) {
                log.info("Imported {} MCP registration(s) of namespace '{}' from files into Postgres; "
                        + "the files are kept but no longer read", imported, scope.namespace().value());
            }
            return imported;
        });
    }

    private List<McpServerRegistration> read(List<ObjectNode> documents) {
        List<McpServerRegistration> out = new ArrayList<>(documents.size());
        for (ObjectNode document : documents) {
            read(document).ifPresent(out::add);
        }
        return List.copyOf(out);
    }

    /** A broken row is skipped with a warning, as a broken file is: one bad registration must not cost the others. */
    private Optional<McpServerRegistration> read(ObjectNode document) {
        String source = servers.table() + " " + scope.namespace().value() + "/" + document.path("id").asText("?");
        try {
            return Optional.of(McpRegistrationJson.read(document, source));
        } catch (RuntimeException e) {
            log.warn("MCP registration {} is unusable and was skipped: {}", source, e.toString());
            return Optional.empty();
        }
    }
}
