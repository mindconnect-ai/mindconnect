package ai.mindconnect.agent.runtime.infrastructure;

import java.nio.file.Path;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.management.adapter.InMemoryAgentRepository;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.runtime.engine.adapter.store.InMemorySessionRepository;
import ai.mindconnect.agent.runtime.engine.port.out.SessionRepository;
import ai.mindconnect.agent.skill.adapter.InMemorySkillRepository;
import ai.mindconnect.agent.skill.port.out.SkillRepository;
import ai.mindconnect.agent.tool.ToolRepository;
import ai.mindconnect.agent.tools.todo.adapter.InMemoryTodoListRepository;
import ai.mindconnect.agent.tools.todo.TodoListRepository;
import ai.mindconnect.agent.tools.usermemory.adapter.InMemoryUserMemoryRepository;
import ai.mindconnect.agent.tools.usermemory.UserMemoryRepository;
import ai.mindconnect.agent.trace.adapter.InMemoryLlmCallTraceRepository;
import ai.mindconnect.agent.trace.port.out.LlmCallTraceRepository;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmRepositoryFactory;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmPriceRepository;
import ai.mindconnect.message.adapter.memory.InMemoryMessageRepositoryFactory;
import ai.mindconnect.message.port.out.ConversationRepository;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.workflow.persistence.memory.InMemoryWorkflowRepositoryFactory;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;

/** Everything in memory, gone with the process: a test, a demo. */
public final class InMemoryStorePlugin extends StorePlugin {

    private final Path sideDirectory;
    private final int maxTracesPerSession;

    /** @param sideDirectory where the few things that are files anyway go — a workflow's own files */
    public InMemoryStorePlugin(Path sideDirectory, int maxTracesPerSession) {
        this.sideDirectory = sideDirectory;
        this.maxTracesPerSession = maxTracesPerSession;
    }

    @Override
    protected boolean keepsToolSettings() {
        return false;
    }

    @Override
    protected Stores stores(ScopeSupplier scope) {
        InMemoryLlmRepositoryFactory llm = new InMemoryLlmRepositoryFactory(scope);
        InMemoryMessageRepositoryFactory messages = new InMemoryMessageRepositoryFactory(scope);
        InMemoryWorkflowRepositoryFactory workflows =
                new InMemoryWorkflowRepositoryFactory(sideDirectory, () -> scope.namespace().value());
        return new Stores() {
            @Override public LlmConfigRepository llmConfigs() { return llm.llmConfigRepository(); }
            @Override public LlmPriceRepository llmPrices() { return llm.llmPriceRepository(); }
            @Override public ConversationRepository conversations() { return messages.conversationRepository(); }
            @Override public MessageRepository messages() { return messages.messageRepository(); }
            @Override public SessionRepository sessions(ScopeSupplier namespaces) {
                return new InMemorySessionRepository(namespaces);
            }
            @Override public AgentRepository agents(ScopeSupplier namespaces) {
                return new InMemoryAgentRepository(namespaces);
            }
            @Override public SkillRepository skills() { return new InMemorySkillRepository(scope); }
            @Override public LlmCallTraceRepository traces() {
                return new InMemoryLlmCallTraceRepository(maxTracesPerSession, scope);
            }
            @Override public TodoListRepository todoLists() { return new InMemoryTodoListRepository(scope); }
            @Override public UserMemoryRepository userMemory() { return new InMemoryUserMemoryRepository(scope); }
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
