package ai.mindconnect.agent.starter.postgres;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.ThreadBoundScope;
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
 * the {@link ScopeSupplier} names when a call comes in.
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

    /**
     * Where this server works: a {@link Scope} bound to the thread per
     * request or per queued task. Until every entry point binds one, an
     * unbound thread works in {@code mindconnect.namespace} (default
     * {@code local}); that fallback goes once the request filter and the
     * start-up routines bind, and an unbound thread becomes an error.
     */
    @Bean
    @ConditionalOnMissingBean(ScopeSupplier.class)
    ThreadBoundScope scopeSupplier(@Value("${mindconnect.namespace:local}") String fallbackNamespace) {
        return ThreadBoundScope.withFallback(Scope.of(new Namespace(fallbackNamespace)));
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
    ai.mindconnect.user.port.out.UserRepository userRepository(Sql mindconnectSql) {
        return new ai.mindconnect.user.adapter.pg.PgUserRepository(mindconnectSql).initSchema();
    }

    @Bean
    ai.mindconnect.user.port.out.ApiTokenRepository apiTokenRepository(Sql mindconnectSql) {
        return new ai.mindconnect.user.adapter.pg.PgApiTokenRepository(mindconnectSql).initSchema();
    }
}
