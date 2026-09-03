package ai.mindconnect.agent.adapter.config;

import ai.mindconnect.agent.adapter.llm.LlmToolResultSummarizer;
import ai.mindconnect.agent.adapter.rule.RuleBasedToolResultSummarizer;
import ai.mindconnect.agent.port.in.AgentTaskRunner;
import ai.mindconnect.agent.memory.port.in.MemoryStrategyFactory;
import ai.mindconnect.agent.port.out.PromptContextProvider;
import ai.mindconnect.agent.port.out.PromptRenderer;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.port.out.ToolResultSummarizer;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.agent.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.tools.todo.TodoListRepository;
import ai.mindconnect.agent.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.agent.tools.workspace.WorkspaceStore;
import ai.mindconnect.agent.service.AgentChatService;
import ai.mindconnect.agent.service.stream.SessionChannels;
import ai.mindconnect.agent.service.task.AgentTurnWorker;
import ai.mindconnect.agent.service.task.ToolCallWorker;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;
import ai.mindconnect.agent.service.AgentRegistryService;
import ai.mindconnect.agent.service.AgentSessionService;
import ai.mindconnect.agent.tool.MapToolEnvironment;
import ai.mindconnect.agent.tool.SpiToolRegistry;
import ai.mindconnect.agent.service.StatelessAgentTaskRunner;
import ai.mindconnect.agent.tools.todo.TodoListService;
import ai.mindconnect.agent.memory.strategy.DefaultMemoryStrategyFactory;
import ai.mindconnect.agent.service.prompt.AgentMetadataProvider;
import ai.mindconnect.agent.service.prompt.AgentToolsProvider;
import ai.mindconnect.agent.service.prompt.CurrentDateProvider;
import ai.mindconnect.agent.adapter.prompt.PebblePromptRenderer;
import ai.mindconnect.agent.service.prompt.WorkspaceNotesProvider;
import ai.mindconnect.agent.adapter.token.TokenCounterRegistry;
import ai.mindconnect.agent.port.out.TokenCounters;
import ai.mindconnect.agent.service.turn.ToolExecutor;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.message.port.in.ConversationManager;
import ai.mindconnect.message.port.out.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class DefaultAgentRuntimeConfig {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentRuntimeConfig.class);

    /**
     * Single base directory for all file-based storage.
     * Override with {@code mindconnect.data.base-dir} in application.properties.
     */
    @Bean
    Path agentStorageDir(@Value("${mindconnect.data.base-dir:data}") String dir) {
        return Path.of(dir);
    }

    @Bean
    TodoListService todoListService(TodoListRepository todoListRepository) {
        return new TodoListService(todoListRepository);
    }

    /**
     * Stateless agent — no session, no memory, no history.
     * <p>
     * Used for internal utility tasks: summarization, title generation, guardrail checks, etc.
     * Resolves a named {@link ai.mindconnect.agent.domain.AgentDefinition} by task name first;
     * falls back to the globally configured default LLM config if none is found.
     * <p>
     * Optional property: {@code mindconnect.agent.stateless.namespace} (default: {@code local})
     * <p>
     * {@code mindconnect.agent.stateless.llm-config-name} is optional at startup — the service
     * starts without it. If it is absent and a task name does not resolve to an
     * {@link ai.mindconnect.agent.domain.AgentDefinition}, an {@link IllegalStateException}
     * is thrown at call time.
     */
    @Bean
    AgentTaskRunner statelessAgentService(
            AgentDefinitionRepository definitionRepository,
            LlmChat llmChat,
            PromptRenderer promptRenderer,
            @Value("${mindconnect.agent.stateless.namespace:local}") String namespaceName,
            @Value("${mindconnect.agent.stateless.llm-config-name:}") String defaultLlmConfigName) {
        Namespace namespace = new Namespace(namespaceName);
        String configName = defaultLlmConfigName.isBlank() ? null : defaultLlmConfigName;
        if (configName == null) {
            log.warn("mindconnect.agent.stateless.llm-config-name not configured — " +
                    "stateless tasks will only work if a matching AgentDefinition exists");
        }
        return new StatelessAgentTaskRunner(definitionRepository, llmChat, namespace, configName, promptRenderer);
    }

    /**
     * Selects the ToolResultSummarizer implementation.
     * <p>
     * Set {@code mindconnect.agent.tool-result.summarizer=llm} to use the LLM-based summarizer
     * (recommended for production — configure a small/fast model as the stateless agent).
     * Defaults to rule-based (no LLM call, zero cost).
     */
    @Bean
    ToolResultSummarizer toolResultSummarizer(
            @Value("${mindconnect.agent.tool-result.summarizer:rule}") String summarizerType,
            AgentTaskRunner runTaskUseCase) {
        if ("llm".equalsIgnoreCase(summarizerType)) {
            log.info("Using LLM tool result summarizer");
            return new LlmToolResultSummarizer(runTaskUseCase);
        }
        log.info("Using rule-based tool result summarizer");
        return new RuleBasedToolResultSummarizer();
    }

    @Bean
    TokenCounters tokenCounterRegistry() {
        return new TokenCounterRegistry();
    }

    // ── Prompt templating ────────────────────────────────────────────────────
    //
    // Each PromptContextProvider contributes variables to the system-prompt template
    // context. New variables = new provider class — Spring picks them up via the
    // List<PromptContextProvider> constructor of PebblePromptRenderer.

    @Bean
    PromptContextProvider currentDateProvider() {
        return new CurrentDateProvider();
    }

    @Bean
    PromptContextProvider agentMetadataProvider() {
        return new AgentMetadataProvider();
    }

    @Bean
    PromptContextProvider agentToolsProvider() {
        return new AgentToolsProvider();
    }

    @Bean
    PromptContextProvider workspaceNotesProvider(WorkspaceStore workspaceStore) {
        return new WorkspaceNotesProvider(workspaceStore);
    }

    @Bean
    PromptRenderer promptRenderer(List<PromptContextProvider> providers) {
        return new PebblePromptRenderer(providers);
    }

    /**
     * How messages read to the model: the host's {@link ai.mindconnect.agent.port.out.LlmMessageMapper}
     * bean when it defines one, the runtime's default otherwise. Define a
     * bean of that type to replace the default; nothing else to configure.
     */
    @Bean
    MemoryStrategyFactory memoryStrategyFactory(ConversationManager conversationManager,
                                                ConversationSummaryRepository conversationSummaryRepository,
                                                ToolResultSummarizer toolResultSummarizer,
                                                AgentTaskRunner runTaskUseCase,
                                                TokenCounters tokenCounterRegistry,
                                                LlmConfigRepository llmConfigRepository,
                                                org.springframework.beans.factory.ObjectProvider<ai.mindconnect.agent.port.out.LlmMessageMapper> messageMapper) {
        return new DefaultMemoryStrategyFactory(conversationManager,
                conversationSummaryRepository, toolResultSummarizer, runTaskUseCase,
                tokenCounterRegistry, llmConfigRepository,
                messageMapper.getIfAvailable(ai.mindconnect.agent.service.MessageToLlmMessageMapper::new));
    }

    /** Session-scoped tool activations written by tool_search, read per round. */
    @Bean
    DynamicToolActivations dynamicToolActivations(
            AgentSessionRepository sessionRepository) {
        return new DynamicToolActivations(sessionRepository);
    }

    @Bean
    ToolRegistry toolRegistry(org.springframework.context.ApplicationContext applicationContext,
                               AgentDefinitionRepository definitionRepository,
                               DynamicToolActivations dynamicToolActivations,
                               AgentSessionRepository sessionRepository,
                               MessageRepository messageRepository,
                               WorkspaceStore workspaceStore,
                               TodoListService todoListService,
                               @Value("${mindconnect.tools.tavily-api-key:}") String tavilyApiKey,
                               @Value("${mindconnect.tools.base-dir:#{systemProperties['user.home']}}") String baseDir,
                               @Value("${mindconnect.data.base-dir:data}") String dataBaseDir,
                               @Value("${mindconnect.workflow-admin.dir:data/workflows}") String workflowDir,
                               @Value("${mindconnect.code-exec.runtime:auto}") String codeExecRuntime,
                               @Value("${mindconnect.code-exec.network:none}") String codeExecNetwork,
                               @Value("${mindconnect.code-exec.languages:}") String codeExecLanguages,
                               @Value("${mindconnect.code-exec.memory:512m}") String codeExecMemory,
                               @Value("${mindconnect.code-exec.cpus:1}") String codeExecCpus,
                               @Value("${mindconnect.code-exec.timeout-seconds:60}") String codeExecTimeoutSeconds,
                               @Value("${mindconnect.code-exec.idle-seconds:600}") String codeExecIdleSeconds,
                               org.springframework.beans.factory.ObjectProvider<ai.mindconnect.llm.port.in.LlmEmbeddings> llmEmbeddings,
                               org.springframework.beans.factory.ObjectProvider<LlmConfigRepository> llmConfigRepository,
                               @Value("${mindconnect.vector-store.backend:memory}") String vectorStoreBackend,
                               @Value("${mindconnect.vector-store.dir:data/vector-stores}") String vectorStoreDir,
                               @Value("${mindconnect.vector-store.url:}") String vectorStoreUrl,
                               @Value("${mindconnect.vector-store.user:}") String vectorStoreUser,
                               @Value("${mindconnect.vector-store.password:}") String vectorStorePassword,
                               @Value("${mindconnect.vector-store.embedding-config:embeddings}") String vectorStoreEmbeddingConfig) {
        // The web tools build their own OkHttpClient internally (with
        // bounded timeouts the SSE-tuned host client wouldn't carry), so
        // the runtime no longer threads any HTTP client through the
        // ToolEnvironment. If a future tool needs the host's shared
        // client, it can take a Qualifier-annotated bean of its own.
        // tool_search needs the registry itself (to search it) — a direct
        // service would be a construction cycle, so a late-bound ref goes into
        // the environment and is set right after the registry exists.
        var registryRef = new ai.mindconnect.agent.tool.ToolRegistryRef();
        MapToolEnvironment env = MapToolEnvironment.builder()
                .service(AgentDefinitionRepository.class, definitionRepository)
                .service(ai.mindconnect.agent.tool.ToolRegistryRef.class, registryRef)
                .service(DynamicToolActivations.class, dynamicToolActivations)
                .service(AgentSessionRepository.class, sessionRepository)
                .service(MessageRepository.class, messageRepository)
                .service(WorkspaceStore.class, workspaceStore)
                .service(TodoListService.class, todoListService)
                .string("defaultBaseDir", baseDir)
                .string("dataBaseDir", dataBaseDir)
                .string("tavilyApiKey", tavilyApiKey)
                // Same directory the embedded workflow admin manages; read by the
                // workflow tool provider (mc-agent-tools-workflow) when present.
                .string("workflowDir", workflowDir)
                // Container-based code execution (mc-agent-tools-code); the
                // factory falls back to sensible defaults for blank values.
                .string("codeExecRuntime", codeExecRuntime)
                .string("codeExecNetwork", codeExecNetwork)
                .string("codeExecLanguages", codeExecLanguages)
                .string("codeExecMemory", codeExecMemory)
                .string("codeExecCpus", codeExecCpus)
                .string("codeExecTimeoutSeconds", codeExecTimeoutSeconds)
                .string("codeExecIdleSeconds", codeExecIdleSeconds)
                // Vector-store knowledge tools (mc-vector-store-tools): backend
                // selection + embedding services; the tools stay unavailable
                // when the host provides no embeddings bean.
                .serviceIfPresent(ai.mindconnect.llm.port.in.LlmEmbeddings.class, llmEmbeddings.getIfAvailable())
                .serviceIfPresent(LlmConfigRepository.class, llmConfigRepository.getIfAvailable())
                .string("vectorStoreBackend", vectorStoreBackend)
                .string("vectorStoreDir", vectorStoreDir)
                .string("vectorStoreUrl", vectorStoreUrl)
                .string("vectorStoreUser", vectorStoreUser)
                .string("vectorStorePassword", vectorStorePassword)
                .string("vectorStoreEmbeddingConfig", vectorStoreEmbeddingConfig)
                .build();
        SpiToolRegistry registry = new SpiToolRegistry(hostBacked(env, applicationContext));
        registryRef.set(registry);
        return registry;
    }

    /**
     * The environment the tools see: the explicit entries above first, and
     * behind them every bean of the host by type. A tool from an optional
     * module — the workflow tools asking for a {@code WorkflowDataRepository},
     * say — thus finds the host's store without this config knowing the
     * module's types. Only an unambiguous bean is served; two candidates
     * read as none, exactly like an absent one.
     */
    private static ai.mindconnect.agent.tool.ToolEnvironment hostBacked(
            MapToolEnvironment explicit, org.springframework.context.ApplicationContext context) {
        return new ai.mindconnect.agent.tool.ToolEnvironment() {
            @Override
            public <T> java.util.Optional<T> get(Class<T> type) {
                return explicit.get(type)
                        .or(() -> java.util.Optional.ofNullable(context.getBeanProvider(type).getIfUnique()));
            }

            @Override
            public java.util.Optional<String> getString(String key) {
                return explicit.getString(key);
            }
        };
    }

    @Bean
    ToolExecutor toolExecutor(java.util.List<ai.mindconnect.agent.tool.ToolAdvisor> advisors) {
        // Spring autowires every ToolAdvisor bean in the context here.
        // ToolExecutor sorts them by order() and runs them as a filter
        // chain around each tool call; empty list = pre-advisor behaviour.
        return new ToolExecutor(advisors);
    }

    /**
     * Virtual-thread executor used by {@code AgentChatService.submitChat}. Sized
     * implicitly by the JVM. Cleanly shut down with the Spring context.
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService turnExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    // ── Use-case services ──────────────────────────────────────────────────

    @Bean
    AgentRegistryService agentRegistryService(AgentDefinitionRepository definitionRepository) {
        return new AgentRegistryService(definitionRepository);
    }

    @Bean
    AgentSessionService agentSessionService(AgentDefinitionRepository definitionRepository,
                                             AgentSessionRepository sessionRepository,
                                             ConversationManager conversationManager,
                                             WorkingMemoryRepository workingMemoryRepository,
                                             ConversationSummaryRepository conversationSummaryRepository,
                                             TodoListRepository todoListRepository,
                                             ai.mindconnect.agent.service.approval.ToolApprovalStore approvalStore) {
        return new AgentSessionService(definitionRepository, sessionRepository,
                conversationManager, workingMemoryRepository, conversationSummaryRepository,
                todoListRepository, approvalStore);
    }

    /**
     * The registry of open sub-agent approval questions — one entry per card
     * the root chat shows. In-memory like the queue: both die together on a
     * restart, so no stale cards can outlive the tasks they point at.
     */
    @Bean
    ai.mindconnect.agent.service.approval.ToolApprovalStore toolApprovalStore() {
        return new ai.mindconnect.agent.service.approval.ToolApprovalStore();
    }

    /**
     * The queue every turn (and later every tool call) runs on. In-memory
     * store for now — a queued turn does not survive a restart, exactly like
     * the executor-based turn before it; the JDBC store is the cluster path.
     */
    @Bean(destroyMethod = "close")
    LocalTaskQueue taskQueue(AgentTurnWorker agentTurnWorker, ToolCallWorker toolCallWorker) {
        LocalTaskQueue queue = new LocalTaskQueue(new InMemoryTaskStore());
        toolCallWorker.attach(queue);                       // awaits sub-agent turns
        queue.register(AgentTurnWorker.TYPE, agentTurnWorker);
        queue.register(ToolCallWorker.TYPE, toolCallWorker);
        return queue;
    }

    @Bean
    AgentTurnWorker agentTurnWorker(ConversationManager conversationManager,
                                    AgentDefinitionRepository definitionRepository,
                                    AgentSessionService sessionService,
                                    MemoryStrategyFactory memoryStrategyFactory,
                                    PromptRenderer promptRenderer,
                                    ToolRegistry toolRegistry,
                                    DynamicToolActivations dynamicToolActivations,
                                    LlmChat llmChat,
                                    LlmCallTraceRepository llmCallTraceRepository,
                                    SessionChannels sessionChannels,
                                    AgentTaskRunner agentTaskRunner,
                                    WorkingMemoryRepository workingMemoryRepository) {
        return new AgentTurnWorker(conversationManager, definitionRepository, sessionService,
                memoryStrategyFactory, promptRenderer, toolRegistry, dynamicToolActivations,
                llmChat, llmCallTraceRepository, sessionChannels,
                agentTaskRunner, workingMemoryRepository);
    }

    @Bean
    ToolCallWorker toolCallWorker(ConversationManager conversationManager,
                                  AgentDefinitionRepository definitionRepository,
                                  AgentSessionService sessionService,
                                  MemoryStrategyFactory memoryStrategyFactory,
                                  ToolRegistry toolRegistry,
                                  DynamicToolActivations dynamicToolActivations,
                                  ToolExecutor toolExecutor,
                                  SessionChannels sessionChannels,
                                  ai.mindconnect.agent.service.approval.ToolApprovalStore approvalStore) {
        return new ToolCallWorker(conversationManager, definitionRepository, sessionService,
                memoryStrategyFactory, toolRegistry, dynamicToolActivations, toolExecutor,
                sessionChannels, approvalStore);
    }

    @Bean
    AgentChatService agentChatService(AgentSessionService sessionService,
                                       AgentDefinitionRepository definitionRepository,
                                       ConversationManager conversationManager,
                                       MemoryStrategyFactory memoryStrategyFactory,
                                       WorkingMemoryRepository workingMemoryRepository,
                                       PromptRenderer promptRenderer,
                                       AgentTaskRunner agentTaskRunner,
                                       SessionChannels sessionChannels,
                                       LocalTaskQueue taskQueue,
                                       ai.mindconnect.agent.service.approval.ToolApprovalStore approvalStore,
                                       ExecutorService turnExecutor) {
        return new AgentChatService(sessionService, definitionRepository, conversationManager,
                memoryStrategyFactory, workingMemoryRepository, promptRenderer,
                agentTaskRunner, sessionChannels, taskQueue, approvalStore, turnExecutor);
    }

    /**
     * The turn streams' channel registry — a bean of its own so SSE/WS
     * adapters can subscribe by turn id instead of holding a consumer
     * reference into a running turn (concept 16, decision 3).
     */
    @Bean
    public SessionChannels sessionChannels() {
        return new SessionChannels();
    }
}
