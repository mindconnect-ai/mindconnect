package ai.mindconnect.agent.runtime.infrastructure.pg;

import java.nio.file.Path;
import java.time.Clock;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.management.adapter.pg.PgAgentRepository;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.runtime.engine.adapter.pg.PgSessionRepository;
import ai.mindconnect.agent.runtime.store.SessionRepository;
import ai.mindconnect.agent.runtime.engine.adapter.pg.PgSessionMessageRepository;
import ai.mindconnect.agent.runtime.store.SessionMessageRepository;
import ai.mindconnect.agent.runtime.infrastructure.StorePlugin;
import ai.mindconnect.agent.skill.adapter.pg.PgSkillRepository;
import ai.mindconnect.agent.skill.port.out.SkillRepository;
import ai.mindconnect.agent.tool.ToolRepository;
import ai.mindconnect.agent.tools.todo.TodoListRepository;
import ai.mindconnect.agent.tools.todo.adapter.pg.PgTodoListRepository;
import ai.mindconnect.agent.tools.usermemory.UserMemoryRepository;
import ai.mindconnect.agent.tools.usermemory.adapter.pg.PgUserMemoryRepository;
import ai.mindconnect.agent.trace.adapter.pg.PgLlmCallTraceRepository;
import ai.mindconnect.agent.trace.port.out.LlmCallTraceRepository;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.adapter.pg.PgFileStoreFactory;
import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.jdbc.Json;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.llm.adapter.pg.PgLlmRepositoryFactory;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
import ai.mindconnect.llm.management.api.port.out.LlmPriceRepository;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.workflow.persistence.pg.PgWorkflowRepositoryFactory;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;

/**
 * Everything in one Postgres database, a namespace column per table. Each
 * repository creates its tables when it is first asked for.
 *
 * <p>Provides the {@link Sql} every Postgres store of the installation works
 * on — the runtime's and those of the plugins beside it — around the runtime's
 * mapper, so a document in the database is the same JSON a file store writes.
 */
public final class PostgresStorePlugin extends StorePlugin {

    private final DataSource dataSource;
    private final Path dataDir;
    private final int maxTracesPerSession;
    private final boolean files;

    /**
     * @param dataDir where the few things that stay files go — a workflow's own files
     * @param files   whether uploads are kept in the database too, instead of the file store configured beside it
     */
    public PostgresStorePlugin(DataSource dataSource, Path dataDir, int maxTracesPerSession, boolean files) {
        this.dataSource = dataSource;
        this.dataDir = dataDir;
        this.maxTracesPerSession = maxTracesPerSession;
        this.files = files;
    }

    @Override
    public void install(PluginRegistry registry) {
        super.install(registry);
        registry.provide(Sql.class, () -> Sql.of(dataSource, new Json(registry.get(ObjectMapper.class))));
        if (files) {
            registry.provide(FileStore.class, () -> new PgFileStoreFactory(registry.get(Sql.class),
                    registry.get(ScopeSupplier.class)).fileStore());
        }
    }

    @Override
    protected SessionMessageRepository sessionMessages(PluginRegistry registry, ScopeSupplier namespaces) {
        return new PgSessionMessageRepository(registry.get(Sql.class), namespaces, Clock.systemUTC()).initSchema();
    }

    @Override
    protected boolean keepsToolSettings() {
        return false;
    }

    @Override
    protected Stores stores(PluginRegistry registry) {
        ScopeSupplier scope = registry.get(ScopeSupplier.class);
        Sql sql = registry.get(Sql.class);
        PgLlmRepositoryFactory llm = new PgLlmRepositoryFactory(sql, scope);
        PgWorkflowRepositoryFactory workflows =
                new PgWorkflowRepositoryFactory(sql, () -> scope.namespace().value(), dataDir,
                        registry.get(JsonMapperFactory.class));
        return new Stores() {
            @Override public LlmConfigRepository llmConfigs() { return llm.llmConfigRepository(); }
            @Override public LlmPriceRepository llmPrices() { return llm.llmPriceRepository(); }
            @Override public SessionRepository sessions(ScopeSupplier namespaces) {
                return new PgSessionRepository(sql, namespaces).initSchema();
            }
            @Override public AgentRepository agents(ScopeSupplier namespaces) {
                return new PgAgentRepository(sql, namespaces).initSchema();
            }
            @Override public SkillRepository skills() { return new PgSkillRepository(sql, scope).initSchema(); }
            @Override public LlmCallTraceRepository traces() {
                return new PgLlmCallTraceRepository(sql, maxTracesPerSession, scope).initSchema();
            }
            @Override public TodoListRepository todoLists() { return new PgTodoListRepository(sql, scope).initSchema(); }
            @Override public UserMemoryRepository userMemory() {
                return new PgUserMemoryRepository(sql, scope).initSchema();
            }
            @Override public WorkflowDataRepository workflows() { return workflows.workflowDataRepository(); }
            @Override public WorkflowInstanceRepository workflowInstances() {
                return workflows.workflowInstanceRepository();
            }
            @Override public ToolRepository toolSettings() {
                throw new UnsupportedOperationException("No tool settings are kept here");
            }
        };
    }
}
