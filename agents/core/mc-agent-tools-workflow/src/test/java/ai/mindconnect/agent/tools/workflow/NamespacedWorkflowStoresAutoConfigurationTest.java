package ai.mindconnect.agent.tools.workflow;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The agents area hands out the workflow stores routed per namespace, and
 * the workflow area's own configurations — file and Postgres alike — back
 * off. The Postgres one did not, once: two beans named
 * {@code workflowDataRepository}, and the server did not start.
 */
class NamespacedWorkflowStoresAutoConfigurationTest {

    @TempDir
    Path dir;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        NamespacedWorkflowStoresAutoConfiguration.class,
                        ai.mindconnect.workflow.admin.WorkflowAdminAutoConfiguration.class,
                        ai.mindconnect.workflow.persistence.pg.WorkflowPostgresAutoConfiguration.class))
                .withUserConfiguration(Host.class)
                .withPropertyValues("mindconnect.data.base-dir=" + dir);
    }

    @Test
    void inFileModeExactlyOneRoutedStorePerPortExists() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WorkflowDataRepository.class);
            assertThat(context).hasSingleBean(WorkflowInstanceRepository.class);
            assertThat(context).hasBean("namespacedWorkflowDataRepository");
        });
    }

    @Test
    void inPostgresModeTheWorkflowAreasOwnBeansBackOff() {
        DataSource db = testDb();
        runner().withPropertyValues("mindconnect.persistence=postgres")
                .withBean(DataSource.class, () -> db)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WorkflowDataRepository.class);
                    assertThat(context).hasSingleBean(WorkflowInstanceRepository.class);
                    assertThat(context).hasBean("namespacedWorkflowDataRepository");
                    assertThat(context).doesNotHaveBean("workflowDataRepository");
                });
    }

    /** A real Postgres, or the test is skipped (see the pg adapter tests for the container). */
    private static DataSource testDb() {
        var ds = new PGSimpleDataSource();
        ds.setUrl(System.getenv().getOrDefault("MC_JDBC_TEST_URL", "jdbc:postgresql://localhost:5433/postgres"));
        ds.setUser(System.getenv().getOrDefault("MC_JDBC_TEST_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("MC_JDBC_TEST_PASSWORD", "test"));
        try (Connection c = ds.getConnection()) {
            assumeTrue(c.isValid(2));
        } catch (Exception e) {
            assumeTrue(false, "no Postgres reachable — skipping");
        }
        return ds;
    }

    @Configuration(proxyBeanMethods = false)
    static class Host {
        @Bean ScopeSupplier scope() { return ScopeSupplier.fixed(new Namespace("test")); }
        @Bean com.fasterxml.jackson.databind.ObjectMapper objectMapper() { return new com.fasterxml.jackson.databind.ObjectMapper(); }
    }
}
