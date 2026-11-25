package ai.mindconnect.agent.runtime.infrastructure;

import java.nio.file.Path;
import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.management.adapter.FileAgentRepository;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.runtime.store.FileSessionRepository;
import ai.mindconnect.agent.runtime.store.SessionRepository;
import ai.mindconnect.agent.runtime.store.FileSessionMessageRepository;
import ai.mindconnect.agent.runtime.store.SessionMessageRepository;
import ai.mindconnect.agent.skill.adapter.FileSkillRepository;
import ai.mindconnect.agent.skill.port.out.SkillRepository;
import ai.mindconnect.agent.tool.ToolRepository;
import ai.mindconnect.agent.tools.todo.adapter.FileTodoListRepository;
import ai.mindconnect.agent.tools.todo.TodoListRepository;
import ai.mindconnect.agent.tools.usermemory.adapter.FileUserMemoryRepository;
import ai.mindconnect.agent.tools.usermemory.UserMemoryRepository;
import ai.mindconnect.agent.trace.adapter.FileLlmCallTraceRepository;
import ai.mindconnect.agent.trace.port.out.LlmCallTraceRepository;
import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.llm.adapter.file.FileLlmRepositoryFactory;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmPriceRepository;
import ai.mindconnect.message.adapter.file.FileMessageRepositoryFactory;
import ai.mindconnect.message.port.out.ConversationRepository;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.workflow.persistence.file.FileWorkflowRepositoryFactory;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;

/** Everything on files under one data directory, a folder per namespace. */
public final class FileStorePlugin extends StorePlugin {

    private final Path dataDir;
    private final int maxTracesPerSession;

    /** @param maxTracesPerSession how many LLM call traces a session keeps before the oldest go */
    public FileStorePlugin(Path dataDir, int maxTracesPerSession) {
        this.dataDir = dataDir;
        this.maxTracesPerSession = maxTracesPerSession;
    }

    @Override
    protected SessionMessageRepository sessionMessages(PluginRegistry registry, ScopeSupplier namespaces) {
        return new FileSessionMessageRepository(dataDir, registry.get(ObjectMapper.class), namespaces,
                Clock.systemUTC());
    }

    @Override
    protected boolean keepsToolSettings() {
        return true;
    }

    @Override
    protected Stores stores(PluginRegistry registry) {
        ScopeSupplier scope = registry.get(ScopeSupplier.class);
        // The host's mapper: the files are written as the host writes JSON.
        ObjectMapper json = registry.get(ObjectMapper.class);
        FileLlmRepositoryFactory llm = new FileLlmRepositoryFactory(dataDir, json, scope);
        FileMessageRepositoryFactory messages = new FileMessageRepositoryFactory(dataDir, json, scope);
        FileWorkflowRepositoryFactory workflows =
                new FileWorkflowRepositoryFactory(dataDir, () -> scope.namespace().value(),
                        registry.get(JsonMapperFactory.class));
        return new Stores() {
            @Override public LlmConfigRepository llmConfigs() { return llm.llmConfigRepository(); }
            @Override public LlmPriceRepository llmPrices() { return llm.llmPriceRepository(); }
            @Override public ConversationRepository conversations() { return messages.conversationRepository(); }
            @Override public MessageRepository messages() { return messages.messageRepository(); }
            @Override public SessionRepository sessions(ScopeSupplier namespaces) {
                return new FileSessionRepository(dataDir, json, namespaces);
            }
            @Override public AgentRepository agents(ScopeSupplier namespaces) {
                return new FileAgentRepository(dataDir, json, namespaces);
            }
            @Override public SkillRepository skills() { return new FileSkillRepository(dataDir, json, scope); }
            @Override public LlmCallTraceRepository traces() {
                return new FileLlmCallTraceRepository(dataDir, json, maxTracesPerSession, scope);
            }
            @Override public TodoListRepository todoLists() { return new FileTodoListRepository(dataDir, json, scope); }
            @Override public UserMemoryRepository userMemory() { return new FileUserMemoryRepository(dataDir, scope); }
            @Override public WorkflowDataRepository workflows() { return workflows.workflowDataRepository(); }
            @Override public WorkflowInstanceRepository workflowInstances() {
                return workflows.workflowInstanceRepository();
            }
            @Override public ToolRepository toolSettings() { return new FileToolRepository(dataDir, json, scope); }
        };
    }
}
