package ai.mindconnect.agent.runtime.infrastructure.pg;

import java.nio.file.Path;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.management.adapter.pg.PgAgentRepository;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.engine.adapter.pg.PgSessionRepository;
import ai.mindconnect.agent.runtime.engine.port.out.SessionRepository;
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
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.llm.adapter.pg.PgLlmRepositoryFactory;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmPriceRepository;
import ai.mindconnect.message.adapter.pg.PgMessageRepositoryFactory;
import ai.mindconnect.message.port.out.ConversationRepository;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.workflow.persistence.pg.PgWorkflowRepositoryFactory;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;

/**
 * Everything in one Postgres database, a namespace column per table. Each
 * repository creates its tables when it is first asked for.
 */
public final class PostgresStorePlugin extends StorePlugin {

    private final Sql sql;
    private final Path dataDir;
    private final int maxTracesPerSession;
    private final boolean files;

    /**
     * @param dataDir where the few things that stay files go — a workflow's own files
     * @param files   whether uploads are kept in the database too, instead of the file store configured beside it
     */
    public PostgresStorePlugin(Sql sql, Path dataDir, int maxTracesPerSession, boolean files) {
        this.sql = sql;
        this.dataDir = dataDir;
        this.maxTracesPerSession = maxTracesPerSession;
        this.files = files;
    }

    @Override
    public void install(PluginRegistry registry) {
        super.install(registry);
        registry.provide(Sql.class, () -> sql);
        if (files) {
            registry.provide(FileStore.class,
                    () -> new PgFileStoreFactory(sql, registry.get(ScopeSupplier.class)).fileStore());
        }
    }

    @Override
    protected boolean keepsToolSettings() {
        return false;
    }

    @Override
    protected Stores stores(ScopeSupplier scope) {
        PgLlmRepositoryFactory llm = new PgLlmRepositoryFactory(sql, scope);
        PgMessageRepositoryFactory messages = new PgMessageRepositoryFactory(sql, scope);
        PgWorkflowRepositoryFactory workflows =
                new PgWorkflowRepositoryFactory(sql, () -> scope.namespace().value(), dataDir);
        return new Stores() {
            @Override public LlmConfigRepository llmConfigs() { return llm.llmConfigRepository(); }
            @Override public LlmPriceRepository llmPrices() { return llm.llmPriceRepository(); }
            @Override public ConversationRepository conversations() { return messages.conversationRepository(); }
            @Override public MessageRepository messages() { return messages.messageRepository(); }
            @Override public SessionRepository sessions(NamespaceProvider namespaces) {
                return new PgSessionRepository(sql, namespaces).initSchema();
            }
            @Override public AgentRepository agents(NamespaceProvider namespaces) {
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
