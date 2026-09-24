package ai.mindconnect.mcp.gateway.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.mcp.gateway.McpServerRegistration;
import ai.mindconnect.mcp.gateway.local.FileMcpServerRepository;
import ai.mindconnect.mcp.gateway.local.McpServerRepository;
import ai.mindconnect.mcp.gateway.local.McpStartPolicy;
import ai.mindconnect.mcp.gateway.local.NamespacedMcpGateways;
import ai.mindconnect.mcp.proxy.McpSessionRegistry;
import ai.mindconnect.mcp.proxy.SdkMcpProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static ai.mindconnect.mcp.gateway.adapter.pg.PgMcpServerRepositoryTest.registration;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An installation that moves to Postgres has its MCP servers in files —
 * {@code <data>/<namespace>/system/mcp-servers/*.json}. Losing them on the
 * switch would drop every configured server, so each namespace's files are
 * imported the first time the namespace is used, and only then.
 */
class PgMcpImportTest {

    private static final Namespace ACME = new Namespace("acme");
    private static final Namespace OTHER = new Namespace("other");

    @TempDir
    Path dataDir;

    private Sql sql;
    private PgMcpStoreFactory stores;

    @BeforeEach
    void setUp() {
        sql = TestDb.freshMcpTables();
        stores = new PgMcpStoreFactory(sql, dataDir);
    }

    @Test
    void a_namespaces_files_are_imported_on_first_use_and_kept() {
        FileMcpServerRepository files = new FileMcpServerRepository(dataDir, ACME);
        files.save(registration("gmail", "Gmail"));
        files.save(registration("files", "Files"));

        McpServerRepository imported = stores.serverRepository(ACME);

        assertThat(imported.findAll()).isEqualTo(files.findAll());
        assertThat(files.findAll()).as("the files stay where they were").hasSize(2);
        assertThat(Files.exists(dataDir.resolve("acme/system/mcp-servers/gmail.json"))).isTrue();
    }

    @Test
    void the_import_happens_once() {
        FileMcpServerRepository files = new FileMcpServerRepository(dataDir, ACME);
        files.save(registration("gmail", "Gmail"));
        McpServerRepository first = stores.serverRepository(ACME);

        // Deleted in Postgres, the file still there: a restart must not bring it back.
        first.deleteById(McpServerId.of("gmail"));
        files.save(registration("later", "Later"));

        assertThat(new PgMcpStoreFactory(sql, dataDir).serverRepository(ACME).findAll()).isEmpty();
    }

    @Test
    void a_namespace_already_in_postgres_imports_nothing() {
        new PgMcpServerRepository(sql, ACME).initSchema().save(registration("remote", "Remote"));
        new FileMcpServerRepository(dataDir, ACME).save(registration("gmail", "Gmail"));

        assertThat(stores.serverRepository(ACME).findAll())
                .extracting(McpServerRegistration::id).containsExactly(McpServerId.of("remote"));
    }

    @Test
    void a_namespace_imports_its_own_files_only() {
        new FileMcpServerRepository(dataDir, ACME).save(registration("gmail", "Gmail"));
        new FileMcpServerRepository(dataDir, OTHER).save(registration("files", "Files"));

        assertThat(stores.serverRepository(ACME).findAll())
                .extracting(McpServerRegistration::id).containsExactly(McpServerId.of("gmail"));
        assertThat(stores.serverRepository(OTHER).findAll())
                .extracting(McpServerRegistration::id).containsExactly(McpServerId.of("files"));
    }

    @Test
    void the_files_are_imported_before_the_bundled_registrations_are_seeded() {
        // Seeding writes into the table; had it come first, the namespace would
        // have a row and its files would never be imported.
        new FileMcpServerRepository(dataDir, ACME).save(registration("gmail", "My Gmail"));
        new FileMcpServerRepository(dataDir, ACME).save(registration("files", "Files"));
        SdkMcpProxy proxy = new SdkMcpProxy();
        McpSessionRegistry sessions = new McpSessionRegistry(proxy);
        try (NamespacedMcpGateways gateways = new NamespacedMcpGateways(stores, proxy, sessions,
                "nothing-here", McpStartPolicy.allowAll())) {

            gateways.seed(ACME, "classpath:initial-data/mcp-servers/*.json");

            assertThat(gateways.forNamespace(ACME).repository().findAll())
                    .extracting(McpServerRegistration::displayName).containsExactly("Files", "My Gmail");
        } finally {
            sessions.shutdown();
        }
    }

    @Test
    void a_namespace_without_files_is_seeded_into_the_table() {
        SdkMcpProxy proxy = new SdkMcpProxy();
        McpSessionRegistry sessions = new McpSessionRegistry(proxy);
        try (NamespacedMcpGateways gateways = new NamespacedMcpGateways(stores, proxy, sessions,
                "nothing-here", McpStartPolicy.allowAll())) {

            gateways.seed(ACME, "classpath:initial-data/mcp-servers/*.json");
            gateways.seed(ACME, "classpath:initial-data/mcp-servers/*.json");

            assertThat(new PgMcpServerRepository(sql, ACME).findAll()).singleElement()
                    .satisfies(r -> {
                        assertThat(r.displayName()).isEqualTo("Bundled Gmail");
                        assertThat(r.enabled()).isFalse();
                    });
        } finally {
            sessions.shutdown();
        }
    }
}
