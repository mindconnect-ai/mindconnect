package ai.mindconnect.agent.runtime.infrastructure;

import java.util.function.Supplier;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.management.api.SessionCleanup;
import ai.mindconnect.agent.runtime.engine.port.out.SessionRepository;
import ai.mindconnect.agent.skill.port.out.SkillRepository;
import ai.mindconnect.agent.tool.ToolRepository;
import ai.mindconnect.agent.tools.todo.TodoListRepository;
import ai.mindconnect.agent.tools.usermemory.UserMemoryRepository;
import ai.mindconnect.agent.trace.port.out.LlmCallTraceRepository;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.env.EncryptingLlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmPriceRepository;
import ai.mindconnect.message.port.in.ConversationManager;
import ai.mindconnect.message.port.out.ConversationRepository;
import ai.mindconnect.message.service.ConversationService;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;

/**
 * Where an installation keeps what it keeps: every repository the runtime
 * and its parts ask for, each built once, on first use. A subclass says
 * where — on files, in memory, in a database; this class is the one list of
 * what there is to keep.
 *
 * <p>Every repository works in the namespace of the call it is asked in: it
 * is handed the scope, not a namespace.
 */
public abstract class StorePlugin implements Plugin {

    /** The repositories of one place, for the scope they work in. */
    public interface Stores {

        LlmConfigRepository llmConfigs();

        LlmPriceRepository llmPrices();

        ConversationRepository conversations();

        MessageRepository messages();

        SessionRepository sessions(ScopeSupplier namespaces);

        AgentRepository agents(ScopeSupplier namespaces);

        SkillRepository skills();

        LlmCallTraceRepository traces();

        TodoListRepository todoLists();

        UserMemoryRepository userMemory();

        WorkflowDataRepository workflows();

        WorkflowInstanceRepository workflowInstances();

        /** The operator's tool settings; asked only of a place that {@link #keepsToolSettings() keeps} them. */
        ToolRepository toolSettings();
    }

    @Override
    public String name() {
        return "stores";
    }

    protected abstract Stores stores(ScopeSupplier scope);

    /** Whether the place keeps the operator's tool settings; where it does not, no such repository is provided. */
    protected abstract boolean keepsToolSettings();

    @Override
    public void install(PluginRegistry registry) {
        // One set of stores, made when the first repository is asked for: by then the scope is provided.
        Supplier<Stores> stores = new Supplier<>() {
            private Stores made;

            @Override
            public synchronized Stores get() {
                if (made == null) {
                    made = stores(registry.get(ScopeSupplier.class));
                }
                return made;
            }
        };
        Supplier<ScopeSupplier> namespaces = registry.supplier(ScopeSupplier.class);
        // The store stays plain, the key wraps it: with an encryption key, credentials are kept encrypted.
        registry.provide(LlmConfigRepository.class, () -> registry.find(EncryptionHelper.class)
                .<LlmConfigRepository>map(key -> new EncryptingLlmConfigRepository(stores.get().llmConfigs(), key))
                .orElseGet(() -> stores.get().llmConfigs()));
        registry.provide(LlmPriceRepository.class, () -> stores.get().llmPrices());
        registry.provide(ConversationRepository.class, () -> stores.get().conversations());
        registry.provide(MessageRepository.class, () -> stores.get().messages());
        registry.provide(ConversationManager.class, () -> new ConversationService(
                registry.get(ConversationRepository.class), registry.get(MessageRepository.class)));
        registry.provide(SessionRepository.class, () -> stores.get().sessions(namespaces.get()));
        registry.provide(AgentRepository.class, () -> stores.get().agents(namespaces.get()));
        registry.provide(SkillRepository.class, () -> stores.get().skills());
        registry.provide(LlmCallTraceRepository.class, () -> stores.get().traces());
        registry.provide(TodoListRepository.class, () -> stores.get().todoLists());
        registry.provide(UserMemoryRepository.class, () -> stores.get().userMemory());
        registry.provide(WorkflowDataRepository.class, () -> stores.get().workflows());
        registry.provide(WorkflowInstanceRepository.class, () -> stores.get().workflowInstances());
        if (keepsToolSettings()) {
            registry.provide(ToolRepository.class, () -> stores.get().toolSettings());
        }
        // What is kept for one session goes with it.
        registry.contribute(SessionCleanup.class, session -> {
            ai.mindconnect.agent.SessionId id = session.id();
            registry.get(TodoListRepository.class).deleteBySession(id);
            registry.get(LlmCallTraceRepository.class).deleteBySession(id);
        });
    }
}
