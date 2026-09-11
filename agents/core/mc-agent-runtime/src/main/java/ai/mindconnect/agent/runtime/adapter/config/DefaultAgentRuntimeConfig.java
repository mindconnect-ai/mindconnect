package ai.mindconnect.agent.runtime.adapter.config;

import ai.mindconnect.agent.runtime.adapter.llm.LlmToolResultSummarizer;
import ai.mindconnect.agent.runtime.adapter.rule.RuleBasedToolResultSummarizer;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.port.in.AgentTaskRunner;
import ai.mindconnect.agent.runtime.memory.port.in.MemoryStrategyFactory;
import ai.mindconnect.agent.runtime.port.out.*;
import ai.mindconnect.agent.runtime.service.*;
import ai.mindconnect.agent.runtime.adapter.filestore.FileStorePartContentReader;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.tools.todo.TodoListRepository;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.runtime.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.agent.runtime.tools.workspace.WorkspaceStore;
import ai.mindconnect.agent.runtime.service.stream.SessionChannels;
import ai.mindconnect.agent.runtime.service.stream.UserChannels;
import ai.mindconnect.agent.runtime.service.task.AgentTurnWorker;
import ai.mindconnect.agent.runtime.service.task.ToolCallWorker;
import ai.mindconnect.agent.runtime.service.approval.ToolApprovalStore;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;
import ai.mindconnect.agent.tool.MapToolEnvironment;
import ai.mindconnect.agent.tool.SpiToolRegistry;
import ai.mindconnect.agent.runtime.tools.todo.TodoListService;
import ai.mindconnect.agent.memory.strategy.DefaultMemoryStrategyFactory;
import ai.mindconnect.agent.runtime.service.prompt.AgentMetadataProvider;
import ai.mindconnect.agent.runtime.service.prompt.AgentToolsProvider;
import ai.mindconnect.agent.runtime.service.prompt.CurrentDateProvider;
import ai.mindconnect.agent.runtime.adapter.prompt.PebblePromptRenderer;
import ai.mindconnect.agent.runtime.service.prompt.WorkspaceNotesProvider;
import ai.mindconnect.agent.runtime.adapter.token.TokenCounterRegistry;
import ai.mindconnect.agent.runtime.service.turn.ToolExecutor;
import ai.mindconnect.agent.Namespace;
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
     * Resolves a named {@link AgentDefinition} by task name first;
     * falls back to the globally configured default LLM config if none is found.
     * <p>
     * {@code mindconnect.agent.stateless.llm-config-name} is optional at startup — the service
     * starts without it. If it is absent and a task name does not resolve to an
     * {@link AgentDefinition}, an {@link IllegalStateException}
     * is thrown at call time.
     */
    @Bean
    AgentTaskRunner statelessAgentService(
            AgentDefinitionRepository definitionRepository,
            LlmChat llmChat,
            PromptRenderer promptRenderer,
            @Value("${mindconnect.agent.stateless.llm-config-name:}") String defaultLlmConfigName) {
        String configName = defaultLlmConfigName.isBlank() ? null : defaultLlmConfigName;
        if (configName == null) {
            log.warn("mindconnect.agent.stateless.llm-config-name not configured — " +
                    "stateless tasks will only work if a matching AgentDefinition exists");
        }
        return new StatelessAgentTaskRunner(definitionRepository, llmChat, configName, promptRenderer);
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
     * How messages read to the model: the host's {@link LlmMessageMapper}
     * bean when it defines one, the runtime's default otherwise. Define a
     * bean of that type to replace the default; nothing else to configure.
     */
    /**
     * Where a media part gets its bytes: the host's file store when there is
     * one, nothing otherwise — then every image and document part renders
     * as its placeholder line.
     */
    @Bean
    PartContentReader partContentReader(
            org.springframework.beans.factory.ObjectProvider<ai.mindconnect.filestore.FileStore> fileStore) {
        ai.mindconnect.filestore.FileStore store = fileStore.getIfAvailable();
        return store != null
                ? new FileStorePartContentReader(store)
                : PartContentReader.none();
    }

    @Bean
    MemoryStrategyFactory memoryStrategyFactory(ConversationManager conversationManager,
                                                ConversationSummaryRepository conversationSummaryRepository,
                                                ToolResultSummarizer toolResultSummarizer,
                                                AgentTaskRunner runTaskUseCase,
                                                TokenCounters tokenCounterRegistry,
                                                LlmConfigRepository llmConfigRepository,
                                                PartContentReader partContentReader,
                                                org.springframework.beans.factory.ObjectProvider<LlmMessageMapper> messageMapper) {
        return new DefaultMemoryStrategyFactory(conversationManager,
                conversationSummaryRepository, toolResultSummarizer, runTaskUseCase,
                tokenCounterRegistry, llmConfigRepository,
                messageMapper.getIfAvailable(() ->
                        new MessageToLlmMessageMapper(partContentReader)));
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
                               Namespace namespace,
                               @Value("${mindconnect.tools.tavily-api-key:}") String tavilyApiKey,
                               @Value("${mindconnect.tools.base-dir:#{systemProperties['user.home']}}") String baseDir,
                               @Value("${mindconnect.data.base-dir:data}") String dataBaseDir,
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
                // The namespace the stores are bound to — for tools that open stores of their own (vector, workflow).
                .service(Namespace.class, namespace)
                .service(ai.mindconnect.agent.tool.ToolRegistryRef.class, registryRef)
                .service(DynamicToolActivations.class, dynamicToolActivations)
                .service(AgentSessionRepository.class, sessionRepository)
                .service(MessageRepository.class, messageRepository)
                .service(WorkspaceStore.class, workspaceStore)
                .service(TodoListService.class, todoListService)
                .string("defaultBaseDir", baseDir)
                // The data directory: workflows and memory vector stores live in <dataBaseDir>/<namespace>/.
                .string("dataBaseDir", dataBaseDir)
                .string("tavilyApiKey", tavilyApiKey)
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
                .string("vectorStoreUrl", vectorStoreUrl)
                .string("vectorStoreUser", vectorStoreUser)
                .string("vectorStorePassword", vectorStorePassword)
                .string("vectorStoreEmbeddingConfig", vectorStoreEmbeddingConfig)
                .build();
        // Deferred on purpose: binding asks the environment for services, and
        // this one resolves them from the application context. Doing that from
        // a warm-up thread while the context is still refreshing means queuing
        // behind the very startup that is waiting for the first round. The
        // listener below starts it once the context is up.
        SpiToolRegistry registry = SpiToolRegistry.deferred(hostBacked(env, applicationContext));
        registryRef.set(registry);
        return registry;
    }

    /**
     * Starts the tool warm-up once the application is up. Providers that reach
     * outside the process bind on their own threads from here, and the ones
     * that are in-process are ready a fraction of a second later — nothing in
     * the startup path waits for either.
     */
    @Bean
    org.springframework.context.ApplicationListener<
            org.springframework.context.event.ContextRefreshedEvent> toolWarmUpStarter(
            ai.mindconnect.agent.tool.ToolRegistry toolRegistry) {
        // ContextRefreshedEvent rather than Boot's ApplicationReadyEvent: this
        // module carries spring-context alone, and by the time it fires every
        // singleton is built — which is the property that matters here. The
        // warm-up itself only ever runs once, however often the event comes.
        return event -> {
            if (toolRegistry instanceof SpiToolRegistry spi) spi.warmUp();
        };
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
                                             ToolApprovalStore approvalStore,
                                             UserChannels userChannels) {
        return new AgentSessionService(definitionRepository, sessionRepository,
                conversationManager, workingMemoryRepository, conversationSummaryRepository,
                todoListRepository, approvalStore, userChannels);
    }

    /**
     * The registry of open sub-agent approval questions — one entry per card
     * the root chat shows. In-memory like the queue: both die together on a
     * restart, so no stale cards can outlive the tasks they point at.
     */
    @Bean
    ToolApprovalStore toolApprovalStore() {
        return new ToolApprovalStore();
    }

    /**
     * The queue every turn (and later every tool call) runs on. In-memory
     * store for now — a queued turn does not survive a restart, exactly like
     * the executor-based turn before it; the JDBC store is the cluster path.
     */
    @Bean(destroyMethod = "close")
    LocalTaskQueue taskQueue(AgentTurnWorker agentTurnWorker, ToolCallWorker toolCallWorker) {
        LocalTaskQueue queue = new LocalTaskQueue(new InMemoryTaskStore());
        // A failed task is otherwise visible only in the task dialog, and only while it is recent.
        queue.addListener(ai.mindconnect.taskqueue.LoggingTaskListener.failuresOnly());
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
                                  ToolApprovalStore approvalStore,
                                  UserChannels userChannels) {
        return new ToolCallWorker(conversationManager, definitionRepository, sessionService,
                memoryStrategyFactory, toolRegistry, dynamicToolActivations, toolExecutor,
                sessionChannels, approvalStore, userChannels);
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
                                      UserChannels userChannels,
                                      LocalTaskQueue taskQueue,
                                      ToolApprovalStore approvalStore,
                                      ExecutorService turnExecutor) {
        return new AgentChatService(sessionService, definitionRepository, conversationManager,
                memoryStrategyFactory, workingMemoryRepository, promptRenderer,
                agentTaskRunner, sessionChannels, userChannels, taskQueue, approvalStore, turnExecutor);
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

    /**
     * The users' streams — the coarse feed (session opened, turn started
     * and finished, approval pending) a client keeps attached while it is
     * not looking at any particular session. A bean of its own for the same
     * reason as the session channels: SSE adapters subscribe by user id.
     */
    @Bean
    public UserChannels userChannels() {
        return new UserChannels();
    }
}
