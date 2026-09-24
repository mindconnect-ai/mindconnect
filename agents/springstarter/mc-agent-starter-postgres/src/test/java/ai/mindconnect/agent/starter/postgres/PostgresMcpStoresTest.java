package ai.mindconnect.agent.starter.postgres;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.mcp.gateway.adapter.pg.PgMcpServerRepository;
import ai.mindconnect.mcp.gateway.local.McpGatewayAutoConfiguration;
import ai.mindconnect.mcp.gateway.local.McpStoreFactory;
import ai.mindconnect.mcp.gateway.local.NamespacedMcpGateways;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Under {@code mindconnect.persistence=postgres} the MCP gateway keeps its
 * registrations in tables — a condition, and a condition is only provable by
 * starting a context. Needs a real Postgres
 * ({@code podman run -d -p 5433:5432 -e POSTGRES_PASSWORD=test pgvector/pgvector:pg17-trixie});
 * skipped without one.
 */
class PostgresMcpStoresTest {

    private static final String URL = System.getenv().getOrDefault(
            "MC_JDBC_TEST_URL", "jdbc:postgresql://localhost:5433/postgres");
    private static final String USER = System.getenv().getOrDefault("MC_JDBC_TEST_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("MC_JDBC_TEST_PASSWORD", "test");

    @TempDir
    Path dataDir;

    @BeforeEach
    void requirePostgres() {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD)) {
            assumeTrue(c.isValid(2));
        } catch (Exception e) {
            assumeTrue(false, "no Postgres reachable at " + URL + " — skipping");
        }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PostgresPersistenceConfig.class, McpGatewayAutoConfiguration.class))
                // The conversion service of a real Boot application, for @Value Durations — see
                // McpGatewayAutoConfigurationTest.
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(
                        "mindconnect.persistence=postgres",
                        "mindconnect.postgres.url=" + URL,
                        "mindconnect.postgres.username=" + USER,
                        "mindconnect.postgres.password=" + PASSWORD,
                        "mindconnect.postgres.pool-size=2",
                        "mindconnect.data.base-dir=" + dataDir,
                        // Seeds go here rather than into the test database's "local".
                        "mindconnect.namespace=starter-mcp-test",
                        "mindconnect.mcp.container-runtime=nothing-here");
    }

    @Test
    void the_gateway_keeps_its_registrations_in_postgres() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(McpStoreFactory.class);
            assertThat(context.getBean(NamespacedMcpGateways.class)
                    .forNamespace(new Namespace("starter-mcp-test")).repository())
                    .isInstanceOf(PgMcpServerRepository.class);
        });
    }

    @Test
    void with_the_gateway_switched_off_there_are_no_mcp_tables_to_serve() {
        runner().withPropertyValues("mindconnect.mcp.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(McpStoreFactory.class);
            assertThat(context).doesNotHaveBean(NamespacedMcpGateways.class);
        });
    }
}
