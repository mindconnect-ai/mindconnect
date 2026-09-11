package ai.mindconnect.agent.starter.postgres;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.runtime.adapter.pg.PgAgentDefinitionRepository;
import ai.mindconnect.agent.runtime.adapter.pg.PgAgentSessionRepository;
import ai.mindconnect.agent.runtime.adapter.pg.PgConversationSummaryRepository;
import ai.mindconnect.agent.runtime.adapter.pg.PgLlmCallTraceRepository;
import ai.mindconnect.agent.runtime.adapter.pg.PgTodoListRepository;
import ai.mindconnect.agent.runtime.adapter.pg.PgWorkingMemoryRepository;
import ai.mindconnect.agent.runtime.adapter.pg.PgWorkspaceStore;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.runtime.tools.todo.TodoListRepository;
import ai.mindconnect.agent.runtime.tools.workspace.WorkspaceStore;
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
     * The one namespace this JVM runs in ({@code mindconnect.namespace}, default
     * {@code local}). Every repository is bound to it when it is built; nothing
     * above the repositories names it.
     */
    @Bean
    Namespace mindconnectNamespace(@Value("${mindconnect.namespace:local}") String namespace) {
        return new Namespace(namespace);
    }

    @Bean
    Sql mindconnectSql(DataSource mindconnectDataSource, ObjectMapper objectMapper) {
        return Sql.of(mindconnectDataSource, new Json(objectMapper));
    }

    // ── agent runtime ───────────────────────────────────────────────────────

    @Bean
    AgentDefinitionRepository agentDefinitionRepository(Sql mindconnectSql, Namespace namespace) {
        return new PgAgentDefinitionRepository(mindconnectSql, namespace).initSchema();
    }

    @Bean
    AgentSessionRepository agentSessionRepository(Sql mindconnectSql, Namespace namespace) {
        return new PgAgentSessionRepository(mindconnectSql, namespace).initSchema();
    }

    @Bean
    LlmCallTraceRepository llmCallTraceRepository(
            Sql mindconnectSql, Namespace namespace,
            @Value("${mindconnect.agent.trace.max-per-session:50}") int maxPerConversation) {
        return new PgLlmCallTraceRepository(mindconnectSql, maxPerConversation, namespace).initSchema();
    }

    @Bean
    TodoListRepository todoListRepository(Sql mindconnectSql, Namespace namespace) {
        return new PgTodoListRepository(mindconnectSql, namespace).initSchema();
    }

    @Bean
    ConversationSummaryRepository conversationSummaryRepository(Sql mindconnectSql, Namespace namespace) {
        return new PgConversationSummaryRepository(mindconnectSql, namespace).initSchema();
    }

    @Bean
    WorkingMemoryRepository workingMemoryRepository(Sql mindconnectSql, Namespace namespace) {
        return new PgWorkingMemoryRepository(mindconnectSql, namespace).initSchema();
    }

    @Bean
    WorkspaceStore workspaceStore(Sql mindconnectSql, Namespace namespace) {
        return new PgWorkspaceStore(mindconnectSql, namespace).initSchema();
    }

    // ── messages ────────────────────────────────────────────────────────────

    @Bean
    ConversationRepository conversationRepository(Sql mindconnectSql, Namespace namespace) {
        return new PgConversationRepository(mindconnectSql, namespace).initSchema();
    }

    @Bean
    MessageRepository messageRepository(Sql mindconnectSql, Namespace namespace) {
        return new PgMessageRepository(mindconnectSql, namespace).initSchema();
    }

    // ── files ───────────────────────────────────────────────────────────────

    /**
     * Uploads follow the persistence switch: in the database unless
     * {@code mindconnect.file-store.backend} names another backend — a host
     * may well keep its records in Postgres and its files on a volume.
     */
    @Bean
    FileStore fileStore(Sql mindconnectSql, Namespace namespace,
                        @Value("${mindconnect.file-store.backend:postgres}") String backend,
                        @Value("${mindconnect.data.base-dir:data}") String baseDir) {
        if ("postgres".equals(backend)) {
            return new PgFileStore(mindconnectSql, namespace).initSchema();
        }
        return FileStoreBackend.byType(backend)
                .orElseThrow(() -> new IllegalStateException("No file-store backend '" + backend
                        + "' on the classpath (available: "
                        + FileStoreBackend.discover().stream().map(FileStoreBackend::type).toList() + ")"))
                .open(Map.of("baseDir", baseDir, "namespace", namespace.value()));
    }

    // ── llm ─────────────────────────────────────────────────────────────────

    /** Encrypted at rest exactly as the file store is: the decorator does not care where the rows live. */
    @Bean
    LlmConfigRepository llmConfigRepository(Sql mindconnectSql, Namespace namespace, EncryptionHelper encryptionHelper) {
        return new EncryptingLlmConfigRepository(
                new PgLlmConfigRepository(mindconnectSql, namespace).initSchema(), encryptionHelper);
    }
}
