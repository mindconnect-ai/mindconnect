package ai.mindconnect.agent.starter.postgres;

import ai.mindconnect.agent.adapter.pg.PgAgentDefinitionRepository;
import ai.mindconnect.agent.adapter.pg.PgAgentSessionRepository;
import ai.mindconnect.agent.adapter.pg.PgConversationSummaryRepository;
import ai.mindconnect.agent.adapter.pg.PgLlmCallTraceRepository;
import ai.mindconnect.agent.adapter.pg.PgTodoListRepository;
import ai.mindconnect.agent.adapter.pg.PgWorkingMemoryRepository;
import ai.mindconnect.agent.adapter.pg.PgWorkspaceStore;
import ai.mindconnect.agent.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.agent.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.tools.todo.TodoListRepository;
import ai.mindconnect.agent.tools.workspace.WorkspaceStore;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.FileStoreBackend;
import ai.mindconnect.filestore.pg.PgFileStore;
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

    @Bean
    Sql mindconnectSql(DataSource mindconnectDataSource, ObjectMapper objectMapper) {
        return Sql.of(mindconnectDataSource, new Json(objectMapper));
    }

    // ── agent runtime ───────────────────────────────────────────────────────

    @Bean
    AgentDefinitionRepository agentDefinitionRepository(Sql mindconnectSql) {
        return new PgAgentDefinitionRepository(mindconnectSql).initSchema();
    }

    @Bean
    AgentSessionRepository agentSessionRepository(Sql mindconnectSql) {
        return new PgAgentSessionRepository(mindconnectSql).initSchema();
    }

    @Bean
    LlmCallTraceRepository llmCallTraceRepository(
            Sql mindconnectSql,
            @Value("${mindconnect.agent.trace.max-per-session:50}") int maxPerConversation) {
        return new PgLlmCallTraceRepository(mindconnectSql, maxPerConversation).initSchema();
    }

    @Bean
    TodoListRepository todoListRepository(Sql mindconnectSql) {
        return new PgTodoListRepository(mindconnectSql).initSchema();
    }

    @Bean
    ConversationSummaryRepository conversationSummaryRepository(Sql mindconnectSql) {
        return new PgConversationSummaryRepository(mindconnectSql).initSchema();
    }

    @Bean
    WorkingMemoryRepository workingMemoryRepository(Sql mindconnectSql) {
        return new PgWorkingMemoryRepository(mindconnectSql).initSchema();
    }

    @Bean
    WorkspaceStore workspaceStore(Sql mindconnectSql) {
        return new PgWorkspaceStore(mindconnectSql).initSchema();
    }

    // ── messages ────────────────────────────────────────────────────────────

    @Bean
    ConversationRepository conversationRepository(Sql mindconnectSql) {
        return new PgConversationRepository(mindconnectSql).initSchema();
    }

    @Bean
    MessageRepository messageRepository(Sql mindconnectSql) {
        return new PgMessageRepository(mindconnectSql).initSchema();
    }

    // ── files ───────────────────────────────────────────────────────────────

    /**
     * Uploads follow the persistence switch: in the database unless
     * {@code mindconnect.file-store.backend} names another backend — a host
     * may well keep its records in Postgres and its files on a volume.
     */
    @Bean
    FileStore fileStore(Sql mindconnectSql,
                        @Value("${mindconnect.file-store.backend:postgres}") String backend,
                        @Value("${mindconnect.file-store.dir:data/files}") String dir) {
        if ("postgres".equals(backend)) {
            return new PgFileStore(mindconnectSql).initSchema();
        }
        return FileStoreBackend.byType(backend)
                .orElseThrow(() -> new IllegalStateException("No file-store backend '" + backend
                        + "' on the classpath (available: "
                        + FileStoreBackend.discover().stream().map(FileStoreBackend::type).toList() + ")"))
                .open(Map.of("dir", dir));
    }

    // ── llm ─────────────────────────────────────────────────────────────────

    /** Encrypted at rest exactly as the file store is: the decorator does not care where the rows live. */
    @Bean
    LlmConfigRepository llmConfigRepository(Sql mindconnectSql, EncryptionHelper encryptionHelper) {
        return new EncryptingLlmConfigRepository(
                new PgLlmConfigRepository(mindconnectSql).initSchema(), encryptionHelper);
    }
}
