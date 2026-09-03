package ai.mindconnect.workflow.persistence.pg;

import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Workflow definitions and suspended instances in Postgres, switched on by
 * {@code mindconnect.persistence=postgres} — the same switch the agent
 * runtime uses, so one setting moves an application as a whole.
 *
 * <p>Uses the application's {@link DataSource} when there is one (the agent
 * apps define it) and opens its own pool from {@code mindconnect.postgres.*}
 * otherwise, so the standalone workflow admin app needs nothing more than
 * the properties.
 */
// After the agent runtime's Postgres config when both are present, so its
// pool is found and not a second one opened; harmless when it is absent.
@AutoConfiguration(afterName = "ai.mindconnect.agent.starter.postgres.PostgresPersistenceConfig")
@ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "postgres")
public class WorkflowPostgresAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPostgresAutoConfiguration.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(DataSource.class)
    DataSource mindconnectDataSource(@Value("${mindconnect.postgres.url}") String url,
                                     @Value("${mindconnect.postgres.username:}") String username,
                                     @Value("${mindconnect.postgres.password:}") String password,
                                     @Value("${mindconnect.postgres.pool-size:10}") int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        if (!username.isBlank()) config.setUsername(username);
        if (!password.isBlank()) config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setPoolName("mindconnect");
        log.info("Workflow persistence: postgres at {}", url.replaceAll("password=[^&]*", "password=***"));
        return new HikariDataSource(config);
    }

    @Bean
    WorkflowDataRepository workflowDataRepository(DataSource dataSource) {
        return new PgWorkflowDataRepository(Sql.of(dataSource)).initSchema();
    }

    @Bean
    WorkflowInstanceRepository workflowInstanceRepository(DataSource dataSource) {
        return new PgWorkflowInstanceRepository(Sql.of(dataSource)).initSchema();
    }
}
