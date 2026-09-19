package ai.mindconnect.agent.starter.postgres;

import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.jdbc.Json;
import ai.mindconnect.jdbc.Sql;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;

import javax.sql.DataSource;

/**
 * Every repository port on Postgres, switched on by
 * {@code mindconnect.persistence=postgres}. The file stores carry the
 * matching {@code =file} condition, so exactly one set of beans exists.
 * Registered as a Spring Boot auto-configuration: having this module on the
 * classpath is enough, no {@code @Import} in the application.
 *
 * <p>One pooled {@link DataSource}, one {@link Sql} around the application's
 * {@link ObjectMapper} — so a document in the database is the same JSON the
 * file store would have written — and every store creates its own tables on
 * start ({@code CREATE TABLE IF NOT EXISTS}; no migration tool).
 *
 * <p>Every store is routed per namespace: the bean is a {@link NamespaceRouted}
 * proxy, the adapter behind it is built — schema included — for the namespace
 * the scope of the call names, when the namespace starter binds one.
 *
 * <pre>
 * mindconnect:
 *   persistence: postgres
 *   postgres:
 *     url: jdbc:postgresql://localhost:5432/mindconnect
 *     username: mindconnect
 *     password: …
 * </pre>
 */
@AutoConfiguration
@ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "postgres")
public class PostgresPersistenceConfig {

    private static final Logger log = LoggerFactory.getLogger(PostgresPersistenceConfig.class);

    @Bean(destroyMethod = "close")
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
        log.info("Persistence: postgres at {}", url.replaceAll("password=[^&]*", "password=***"));
        return new HikariDataSource(config);
    }

    @Bean
    Sql mindconnectSql(DataSource mindconnectDataSource, ObjectMapper objectMapper) {
        return Sql.of(mindconnectDataSource, new Json(objectMapper));
    }

    /** What the runtime starter builds every store of the runtime from: Postgres, side channels under the data dir. */
    @Bean
    @ConditionalOnMissingBean(Persistence.class)
    Persistence persistence(DataSource mindconnectDataSource,
                            @Value("${mindconnect.data.base-dir:data}") String baseDir) {
        return Persistence.postgres(mindconnectDataSource, java.nio.file.Path.of(baseDir));
    }

    // ── users ───────────────────────────────────────────────────────────────

    @Bean
    /** Installation-wide, not per namespace: a user is the same person in every namespace. */
    ai.mindconnect.user.port.out.UserRepository userRepository(
            Sql mindconnectSql,
            org.springframework.beans.factory.ObjectProvider<ai.mindconnect.common.util.encryption.EncryptionHelper> encryption) {
        var rows = new ai.mindconnect.user.adapter.pg.PgUserRepository(mindconnectSql).initSchema();
        var helper = encryption.getIfAvailable();
        // A user's own variables are secrets — enc: at rest when there is a key, like LLM credentials.
        if (helper == null) {
            log.warn("No EncryptionHelper — users' own variables are stored unencrypted");
            return rows;
        }
        return new ai.mindconnect.user.adapter.env.EncryptingUserRepository(rows, helper);
    }

    @Bean
    ai.mindconnect.user.port.out.ApiTokenRepository apiTokenRepository(Sql mindconnectSql) {
        return new ai.mindconnect.user.adapter.pg.PgApiTokenRepository(mindconnectSql).initSchema();
    }

    /** The OAuth apps this installation can connect through — installation-wide. */
    @Bean
    ai.mindconnect.credentials.port.out.OAuthProviderRepository oAuthProviderRepository(
            Sql mindconnectSql,
            org.springframework.beans.factory.ObjectProvider<ai.mindconnect.common.util.encryption.EncryptionHelper> encryption) {
        var rows = new ai.mindconnect.credentials.adapter.pg.PgOAuthProviderRepository(mindconnectSql).initSchema();
        var helper = encryption.getIfAvailable();
        return helper == null ? rows
                : new ai.mindconnect.credentials.adapter.env.EncryptingOAuthProviderRepository(rows, helper);
    }

    /** The tools each user keeps in their own account — installation-wide. */
    @Bean
    ai.mindconnect.user.port.out.UserToolRepository userToolRepository(Sql mindconnectSql) {
        return new ai.mindconnect.user.adapter.pg.PgUserToolRepository(mindconnectSql).initSchema();
    }

    /** The accounts users attached — installation-wide, and encrypted like their variables. */
    @Bean
    ai.mindconnect.credentials.port.out.ConnectionRepository connectionRepository(
            Sql mindconnectSql,
            org.springframework.beans.factory.ObjectProvider<ai.mindconnect.common.util.encryption.EncryptionHelper> encryption) {
        var rows = new ai.mindconnect.credentials.adapter.pg.PgConnectionRepository(mindconnectSql).initSchema();
        var helper = encryption.getIfAvailable();
        if (helper == null) {
            log.warn("No EncryptionHelper — connection credentials are stored unencrypted");
            return rows;
        }
        return new ai.mindconnect.credentials.adapter.env.EncryptingConnectionRepository(rows, helper);
    }

    /** Installation-wide like the users they are addressed to, and not encrypted: a notice is not a secret. */
    @Bean
    ai.mindconnect.user.port.out.NotificationRepository notificationRepository(Sql mindconnectSql) {
        return new ai.mindconnect.user.adapter.pg.PgNotificationRepository(mindconnectSql).initSchema();
    }
}
