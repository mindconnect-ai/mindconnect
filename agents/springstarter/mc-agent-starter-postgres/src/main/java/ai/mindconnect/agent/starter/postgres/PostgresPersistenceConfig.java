package ai.mindconnect.agent.starter.postgres;

import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.agent.tool.TimeZones;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.user.port.out.ApiTokenRepository;
import ai.mindconnect.user.port.out.NotificationRepository;
import ai.mindconnect.user.port.out.PreferenceRepository;
import ai.mindconnect.user.port.out.UserRepository;
import ai.mindconnect.user.port.out.UserToolRepository;
import ai.mindconnect.user.runtime.UserPlugin;
import ai.mindconnect.user.runtime.pg.PgUserPlugin;
import ai.mindconnect.user.service.UserService;
import org.springframework.beans.factory.ObjectProvider;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.AutoConfiguration;

import javax.sql.DataSource;

/**
 * Every repository port on Postgres, switched on by
 * {@code mindconnect.persistence=postgres}. The file stores carry the
 * matching {@code =file} condition, so exactly one set of beans exists.
 * Registered as a Spring Boot auto-configuration: having this module on the
 * classpath is enough, no {@code @Import} in the application.
 *
 * <p>One pooled {@link DataSource}; the runtime builds the one {@link Sql}
 * around its mapper from it — so a document in the database is the same JSON
 * the file store would have written — and every store creates its own tables
 * on start ({@code CREATE TABLE IF NOT EXISTS}; no migration tool).
 *
 * <p>Every store is one adapter whose statements match the namespace the
 * scope of the call names, when the namespace starter binds one.
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

    // ── users ───────────────────────────────────────────────────────────────

    /**
     * The users, their API tokens, tools, notices and preferences as tables, installed
     * into the runtime: the runtime asks a user's zone and own variables, so it holds the
     * stores. Installation-wide, not per namespace: a user is the same person in every namespace.
     */
    @Bean
    @ConditionalOnMissingBean(UserPlugin.class)
    PgUserPlugin pgUserPlugin(ObjectProvider<EncryptionHelper> encryption,
                              @Value("${mindconnect.time-zone:}") String installationZone) {
        return new PgUserPlugin(encryption.getIfAvailable(), installationZone);
    }

    @Bean UserRepository userRepository(AgentRuntime r) { return r.service(UserRepository.class); }
    @Bean ApiTokenRepository apiTokenRepository(AgentRuntime r) { return r.service(ApiTokenRepository.class); }
    @Bean UserToolRepository userToolRepository(AgentRuntime r) { return r.service(UserToolRepository.class); }
    @Bean NotificationRepository notificationRepository(AgentRuntime r) { return r.service(NotificationRepository.class); }
    @Bean PreferenceRepository preferenceRepository(AgentRuntime r) { return r.service(PreferenceRepository.class); }
    @Bean UserService userService(AgentRuntime r) { return r.service(UserService.class); }
    /** The zone each user lives in, for the profile page as for the runtime's prompt and tools. */
    @Bean TimeZones timeZones(AgentRuntime r) { return r.service(TimeZones.class); }

    /** Says what the start-up rewrite of earlier ciphertexts changed — nothing on every start after the first. */
    private static void logRewritten(int records, String what) {
        if (records > 0) log.info("{} {} rewritten from the earlier encryption scheme to AES-GCM", records, what);
    }

    /** The OAuth apps this installation can connect through — installation-wide. */
    @Bean
    ai.mindconnect.credentials.port.out.OAuthProviderRepository oAuthProviderRepository(
            Sql mindconnectSql,
            ObjectProvider<EncryptionHelper> encryption) {
        var rows = new ai.mindconnect.credentials.adapter.pg.PgOAuthProviderRepository(mindconnectSql).initSchema();
        var helper = encryption.getIfAvailable();
        if (helper == null) return rows;
        var encrypting = new ai.mindconnect.credentials.adapter.env.EncryptingOAuthProviderRepository(rows, helper);
        logRewritten(encrypting.rewriteEarlierScheme(), "OAuth provider(s)");
        return encrypting;
    }

    /** The accounts users attached — installation-wide, and encrypted like their variables. */
    @Bean
    ai.mindconnect.credentials.port.out.ConnectionRepository connectionRepository(
            Sql mindconnectSql,
            ObjectProvider<EncryptionHelper> encryption, UserRepository users) {
        var rows = new ai.mindconnect.credentials.adapter.pg.PgConnectionRepository(mindconnectSql).initSchema();
        var helper = encryption.getIfAvailable();
        if (helper == null) {
            log.warn("No EncryptionHelper — connection credentials are stored unencrypted");
            return rows;
        }
        var encrypting = new ai.mindconnect.credentials.adapter.env.EncryptingConnectionRepository(rows, helper);
        logRewritten(encrypting.rewriteEarlierScheme(
                users.findAll().stream().map(ai.mindconnect.user.domain.User::id).toList()), "connection(s)");
        return encrypting;
    }

    // ── MCP gateway ─────────────────────────────────────────────────────────

    /**
     * The in-process MCP gateway's registrations ({@code mc_mcp_server}) and
     * discovery cache ({@code mc_mcp_schema_cache}), per namespace. A
     * namespace's registration files under {@code <mindconnect.data.base-dir>}
     * are imported the first time it is used, and kept. Without this bean the
     * gateway keeps both in files.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "ai.mindconnect.mcp.gateway.adapter.pg.PgMcpStoreFactory")
    @ConditionalOnProperty(prefix = "mindconnect.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class Mcp {
        @Bean
        @ConditionalOnMissingBean(ai.mindconnect.mcp.gateway.local.McpStoreFactory.class)
        ai.mindconnect.mcp.gateway.local.McpStoreFactory mcpStoreFactory(
                Sql mindconnectSql, @Value("${mindconnect.data.base-dir:data}") String baseDir,
                JsonMapperFactory json) {
            return new ai.mindconnect.mcp.gateway.adapter.pg.PgMcpStoreFactory(mindconnectSql,
                    java.nio.file.Path.of(baseDir), json);
        }
    }
}
