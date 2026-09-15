package ai.mindconnect.agent.starter.postgres;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.NamespaceRouted;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.ThreadBoundScope;
import ai.mindconnect.agent.runtime.adapter.pg.PgAgentDefinitionRepository;
import ai.mindconnect.agent.runtime.adapter.pg.PgAgentSessionRepository;
import ai.mindconnect.agent.runtime.adapter.pg.PgConversationSummaryRepository;
import ai.mindconnect.agent.runtime.adapter.pg.PgLlmCallTraceRepository;
import ai.mindconnect.agent.runtime.adapter.pg.PgSkillRepository;
import ai.mindconnect.agent.runtime.adapter.pg.PgTodoListRepository;
import ai.mindconnect.agent.runtime.adapter.pg.PgWorkingMemoryRepository;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.runtime.tools.todo.TodoListRepository;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.FileStoreBackend;
import ai.mindconnect.filestore.adapter.pg.PgFileStore;
import ai.mindconnect.jdbc.Json;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.llm.adapter.file.EncryptingLlmConfigRepository;
import ai.mindconnect.llm.adapter.pg.PgLlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.message.adapter.pg.PgConversationRepository;
import ai.mindconnect.message.adapter.pg.PgMessageRepository;
import ai.mindconnect.message.port.out.ConversationRepository;
import ai.mindconnect.message.port.out.MessageRepository;
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
import java.util.Map;

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

    // ── agent runtime ───────────────────────────────────────────────────────

    @Bean
    AgentDefinitionRepository agentDefinitionRepository(Sql mindconnectSql, ScopeSupplier scope) {
        return NamespaceRouted.route(AgentDefinitionRepository.class, scope, ns -> new PgAgentDefinitionRepository(mindconnectSql, ns).initSchema());
    }

    @Bean
    AgentSessionRepository agentSessionRepository(Sql mindconnectSql, ScopeSupplier scope) {
        return NamespaceRouted.route(AgentSessionRepository.class, scope, ns -> new PgAgentSessionRepository(mindconnectSql, ns).initSchema());
    }

    @Bean
    LlmCallTraceRepository llmCallTraceRepository(
            Sql mindconnectSql, ScopeSupplier scope,
            @Value("${mindconnect.agent.trace.max-per-session:50}") int maxPerConversation) {
        return NamespaceRouted.route(LlmCallTraceRepository.class, scope,
                ns -> new PgLlmCallTraceRepository(mindconnectSql, maxPerConversation, ns).initSchema());
    }

    @Bean
    TodoListRepository todoListRepository(Sql mindconnectSql, ScopeSupplier scope) {
        return NamespaceRouted.route(TodoListRepository.class, scope, ns -> new PgTodoListRepository(mindconnectSql, ns).initSchema());
    }

    @Bean
    ai.mindconnect.agent.runtime.skill.SkillRepository skillRepository(Sql mindconnectSql, ScopeSupplier scope) {
        return NamespaceRouted.route(ai.mindconnect.agent.runtime.skill.SkillRepository.class, scope, ns -> new PgSkillRepository(mindconnectSql, ns).initSchema());
    }

    @Bean
    ConversationSummaryRepository conversationSummaryRepository(Sql mindconnectSql, ScopeSupplier scope) {
        return NamespaceRouted.route(ConversationSummaryRepository.class, scope, ns -> new PgConversationSummaryRepository(mindconnectSql, ns).initSchema());
    }

    @Bean
    WorkingMemoryRepository workingMemoryRepository(Sql mindconnectSql, ScopeSupplier scope) {
        return NamespaceRouted.route(WorkingMemoryRepository.class, scope, ns -> new PgWorkingMemoryRepository(mindconnectSql, ns).initSchema());
    }

    // ── messages ────────────────────────────────────────────────────────────

    @Bean
    ConversationRepository conversationRepository(Sql mindconnectSql, ScopeSupplier scope) {
        return NamespaceRouted.route(ConversationRepository.class, scope, ns -> new PgConversationRepository(mindconnectSql, ns).initSchema());
    }

    @Bean
    MessageRepository messageRepository(Sql mindconnectSql, ScopeSupplier scope) {
        return NamespaceRouted.route(MessageRepository.class, scope, ns -> new PgMessageRepository(mindconnectSql, ns).initSchema());
    }

    // ── files ───────────────────────────────────────────────────────────────

    /**
     * Uploads follow the persistence switch: in the database unless
     * {@code mindconnect.file-store.backend} names another backend — a host
     * may well keep its records in Postgres and its files on a volume.
     */
    @Bean
    FileStore fileStore(Sql mindconnectSql, ScopeSupplier scope,
                        @Value("${mindconnect.file-store.backend:postgres}") String backend,
                        @Value("${mindconnect.data.base-dir:data}") String baseDir) {
        if ("postgres".equals(backend)) {
            return NamespaceRouted.route(FileStore.class, scope, ns -> new PgFileStore(mindconnectSql, ns).initSchema());
        }
        FileStoreBackend files = FileStoreBackend.byType(backend)
                .orElseThrow(() -> new IllegalStateException("No file-store backend '" + backend
                        + "' on the classpath (available: "
                        + FileStoreBackend.discover().stream().map(FileStoreBackend::type).toList() + ")"));
        return NamespaceRouted.route(FileStore.class, scope,
                ns -> files.open(Map.of("baseDir", baseDir, "namespace", ns.value())));
    }

    // ── llm ─────────────────────────────────────────────────────────────────

    /** Encrypted at rest exactly as the file store is: the decorator does not care where the rows live. */
    @Bean
    LlmConfigRepository llmConfigRepository(Sql mindconnectSql, ScopeSupplier scope, EncryptionHelper encryptionHelper) {
        return new EncryptingLlmConfigRepository(NamespaceRouted.route(LlmConfigRepository.class, scope,
                ns -> new PgLlmConfigRepository(mindconnectSql, ns).initSchema()), encryptionHelper);
    }

    // ── users ───────────────────────────────────────────────────────────────

    @Bean
    ai.mindconnect.user.port.out.UserRepository userRepository(Sql mindconnectSql, ScopeSupplier scope) {
        return NamespaceRouted.route(ai.mindconnect.user.port.out.UserRepository.class, scope, ns -> new ai.mindconnect.user.adapter.pg.PgUserRepository(mindconnectSql, ns).initSchema());
    }

    @Bean
    ai.mindconnect.user.port.out.ApiTokenRepository apiTokenRepository(Sql mindconnectSql, ScopeSupplier scope) {
        return NamespaceRouted.route(ai.mindconnect.user.port.out.ApiTokenRepository.class, scope, ns -> new ai.mindconnect.user.adapter.pg.PgApiTokenRepository(mindconnectSql, ns).initSchema());
    }
}
