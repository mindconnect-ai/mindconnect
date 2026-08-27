package ai.mindconnect.agent.builder;

import ai.mindconnect.agent.adapter.file.FileAgentDefinitionRepository;
import ai.mindconnect.agent.adapter.file.FileAgentSessionRepository;
import ai.mindconnect.agent.adapter.file.FileConversationSummaryRepository;
import ai.mindconnect.agent.adapter.file.FileTodoListRepository;
import ai.mindconnect.agent.adapter.file.FileWorkingMemoryRepository;
import ai.mindconnect.agent.adapter.file.FileWorkspaceStore;
import ai.mindconnect.agent.adapter.llm.LlmToolResultSummarizer;
import ai.mindconnect.agent.adapter.token.TokenCounterRegistry;
import ai.mindconnect.agent.adapter.repo.memory.*;
import ai.mindconnect.agent.adapter.rule.RuleBasedToolResultSummarizer;
import ai.mindconnect.agent.domain.AgentDefinition;
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
import ai.mindconnect.agent.tools.workspace.WorkspaceStore;
import ai.mindconnect.agent.service.AgentChatService;
import ai.mindconnect.agent.service.AgentSessionService;
import ai.mindconnect.agent.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.agent.tool.MapToolEnvironment;
import ai.mindconnect.agent.tool.SpiToolRegistry;
import ai.mindconnect.agent.service.StatelessAgentTaskRunner;
import ai.mindconnect.agent.tools.todo.TodoListService;
import ai.mindconnect.agent.tool.ToolRegistryRef;
import ai.mindconnect.agent.memory.strategy.DefaultMemoryStrategyFactory;
import ai.mindconnect.agent.service.prompt.AgentMetadataProvider;
import ai.mindconnect.agent.service.prompt.AgentToolsProvider;
import ai.mindconnect.agent.service.prompt.CurrentDateProvider;
import ai.mindconnect.agent.adapter.prompt.PebblePromptRenderer;
import ai.mindconnect.agent.service.prompt.WorkspaceNotesProvider;
import ai.mindconnect.agent.port.out.TokenCounters;
import ai.mindconnect.agent.service.turn.ToolExecutor;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.anthropic.ClaudeGateway;
import ai.mindconnect.llm.adapter.file.EncryptingLlmConfigRepository;
import ai.mindconnect.llm.adapter.file.FileLlmConfigRepository;
import ai.mindconnect.llm.adapter.gemini.GeminiGateway;
import ai.mindconnect.llm.adapter.openai.AzureOpenAiGateway;
import ai.mindconnect.llm.adapter.openai.OpenAiCompatibleGateway;
import ai.mindconnect.llm.adapter.openai.OpenAiEmbeddingsGateway;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmGateway;
import ai.mindconnect.llm.service.DefaultLlmGatewayRegistry;
import ai.mindconnect.llm.service.RoutingLlmChatService;
import ai.mindconnect.message.port.in.ConversationManager;
import ai.mindconnect.message.port.out.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Assembles a complete, Spring-free {@link AgentRuntime} — the same object
 * graph the Spring apps wire via beans, built by hand so the runtime embeds
 * in any plain Java program:
 *
 * <pre>{@code
 * try (AgentRuntime runtime = AgentRuntimeBuilder.useFilePersistence(Path.of("data"))
 *         .llmConfig(LlmConfig.lmStudio("chat", "google/gemma-4-e4b", "http://localhost:1234"))
 *         .agentDefinitionFromClasspath("demo-agent.json")
 *         .build()) {
 *     System.out.println(runtime.ask("demo-agent", "user", "Hello?", e -> {}));
 * }
 * }</pre>
 *
 * <p>Every aspect is configurable here, but the capability modules stay
 * optional Maven dependencies: tools are discovered via SPI from whatever
 * {@code mc-agent-tools-*} / {@code mc-vector-store-*} modules the client put
 * on its classpath, and their settings travel as plain environment strings
 * ({@link #property(String, String)} plus the named shortcuts). Typed APIs
 * that reference optional modules (e.g. {@link AgentRuntime#attachFile}) link
 * only when those modules are present.
 */
public final class AgentRuntimeBuilder {

    /** Persistence backend. */
    private enum Mode { FILE, IN_MEMORY }

    private final Mode mode;
    private final Path dataDir;   // in IN_MEMORY mode: a temp dir for file-rooted side channels
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private String namespaceName = "local";
    private String defaultLlmConfigName;
    private String encryptionKey;
    private String toolResultSummarizer = "rule";
    private final Map<String, String> environment = new LinkedHashMap<>();
    private final List<LlmConfig> pendingLlmConfigs = new ArrayList<>();
    private final List<AgentDefinition> pendingAgentDefinitions = new ArrayList<>();
    private final List<String> pendingWorkflowResources = new ArrayList<>();

    private AgentRuntimeBuilder(Mode mode, Path dataDir) {
        this.mode = mode;
        this.dataDir = dataDir;
        environment.put("defaultBaseDir", System.getProperty("user.home"));
        environment.put("dataBaseDir", dataDir.toString());
        environment.put("workflowDir", dataDir.resolve("workflows").toString());
        environment.put("vectorStoreDir", dataDir.resolve("vector-stores").toString());
    }

    /** Starts a builder with file persistence rooted at {@code dataDir}. */
    public static AgentRuntimeBuilder useFilePersistence(Path dataDir) {
        return new AgentRuntimeBuilder(Mode.FILE, dataDir);
    }

    /** File persistence under a fresh temp directory (deleted by the OS, not by us). */
    public static AgentRuntimeBuilder useTempPersistence() {
        return new AgentRuntimeBuilder(Mode.FILE, tempDir());
    }

    /**
     * Purely in-memory persistence — nothing survives {@link AgentRuntime#close()}.
     * The simplest possible setup for tests and short-lived embeddings. File-rooted
     * side channels (vector store files, workflow definitions, code-exec scratch)
     * still use a temp directory when their optional modules are present.
     */
    public static AgentRuntimeBuilder useInMemoryPersistence() {
        return new AgentRuntimeBuilder(Mode.IN_MEMORY, tempDir());
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("mc-agent-runtime");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── core settings ──────────────────────────────────────────────────────

    public AgentRuntimeBuilder objectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        return this;
    }

    /** Namespace all agents/sessions live in (default {@code local}). */
    public AgentRuntimeBuilder namespace(String namespace) {
        this.namespaceName = namespace;
        return this;
    }

    /** LLM config for internal stateless tasks; defaults to the single registered config. */
    public AgentRuntimeBuilder defaultLlmConfigName(String name) {
        this.defaultLlmConfigName = name;
        return this;
    }

    /** Enables at-rest encryption of stored LLM API keys (16-char AES key). */
    public AgentRuntimeBuilder encryptionKey(String secretKey) {
        this.encryptionKey = secretKey;
        return this;
    }

    /** {@code "rule"} (default, no LLM) or {@code "llm"} for tool-result summarization. */
    public AgentRuntimeBuilder toolResultSummarizer(String type) {
        this.toolResultSummarizer = type;
        return this;
    }

    // ── tool environment (plain strings — works without optional modules) ──

    /**
     * Sets one tool-environment string — the same keys the Spring apps feed
     * from {@code mindconnect.*} properties: {@code defaultBaseDir},
     * {@code tavilyApiKey}, {@code workflowDir}, {@code codeExecRuntime},
     * {@code vectorStoreBackend}, {@code vectorStoreEmbeddingConfig}, ...
     */
    public AgentRuntimeBuilder property(String key, String value) {
        environment.put(key, value);
        return this;
    }

    /** Base directory file-rooted tools operate in (default: user home). */
    public AgentRuntimeBuilder toolsBaseDir(Path dir) {
        return property("defaultBaseDir", dir.toString());
    }

    public AgentRuntimeBuilder tavilyApiKey(String key) {
        return property("tavilyApiKey", key);
    }

    /** Container runtime for code execution: {@code auto} | {@code docker} | {@code podman}. */
    public AgentRuntimeBuilder codeExecutionRuntime(String runtime) {
        return property("codeExecRuntime", runtime);
    }

    /** Vector store backend ({@code memory} | {@code pgvector}) for the knowledge tools. */
    public AgentRuntimeBuilder vectorStoreBackend(String backend) {
        return property("vectorStoreBackend", backend);
    }

    /** Name of the LlmConfig used for embeddings (default {@code embeddings}). */
    public AgentRuntimeBuilder embeddingConfigName(String name) {
        return property("vectorStoreEmbeddingConfig", name);
    }

    // ── seeding ────────────────────────────────────────────────────────────

    public AgentRuntimeBuilder llmConfig(LlmConfig config) {
        pendingLlmConfigs.add(config);
        return this;
    }

    public AgentRuntimeBuilder llmConfigFromClasspath(String resource) {
        return llmConfig(readClasspath(resource, LlmConfig.class));
    }

    public AgentRuntimeBuilder agentDefinition(AgentDefinition definition) {
        pendingAgentDefinitions.add(definition);
        return this;
    }

    public AgentRuntimeBuilder agentDefinitionFromClasspath(String resource) {
        return agentDefinition(readClasspath(resource, AgentDefinition.class));
    }

    /**
     * Copies a workflow JSON from the classpath into the workflow directory
     * (file name = resource base name) — e.g. the {@code file-ingestion}
     * pipeline the attach support runs. Needs {@code mc-agent-tools-workflow}
     * for workflows-as-tools and {@code mc-workflow-admin-rest} for
     * {@link AgentRuntime#attachFile}.
     */
    public AgentRuntimeBuilder workflowFromClasspath(String resource) {
        pendingWorkflowResources.add(resource);
        return this;
    }

    // ── build ──────────────────────────────────────────────────────────────

    public AgentRuntime build() {
        boolean inMemory = mode == Mode.IN_MEMORY;

        // 1. Persistence — file-based rooted at dataDir, or purely in-memory.
        WorkspaceStore workspaceStore = inMemory
                ? new InMemoryWorkspaceStore()
                : new FileWorkspaceStore(dataDir);
        WorkingMemoryRepository workingMemoryRepository = inMemory
                ? new InMemoryWorkingMemoryRepository()
                : new FileWorkingMemoryRepository(dataDir);
        ConversationSummaryRepository summaryRepository = inMemory
                ? new InMemoryConversationSummaryRepository()
                : new FileConversationSummaryRepository(dataDir);
        TodoListRepository todoListRepository = inMemory
                ? new InMemoryTodoListRepository()
                : new FileTodoListRepository(dataDir);
        AgentDefinitionRepository definitionRepository = inMemory
                ? new InMemoryAgentDefinitionRepository()
                : new FileAgentDefinitionRepository(dataDir, objectMapper);
        AgentSessionRepository sessionRepository = inMemory
                ? new InMemoryAgentSessionRepository()
                : new FileAgentSessionRepository(dataDir, objectMapper);

        // 2. Messages / conversations.
        ConversationManager conversationManager;
        MessageRepository messageRepository;
        if (inMemory) {
            var messageStore = new ai.mindconnect.message.adapter.memory.InMemoryMessageStore();
            conversationManager = messageStore.conversationManager();
            messageRepository = messageStore.messageRepository();
        } else {
            var conversationRepository = new ai.mindconnect.message.adapter.file.FileConversationRepository(dataDir, objectMapper);
            messageRepository = new ai.mindconnect.message.adapter.file.FileMessageRepository(dataDir, objectMapper);
            conversationManager = new ai.mindconnect.message.service.ConversationService(
                    conversationRepository, messageRepository);
        }

        // 3. LLM layer: encrypted config repo + routing chat over all gateways.
        EncryptionHelper encryption = new EncryptionHelper(encryptionKey);
        // Without a key, skip the encrypting wrapper entirely — api keys are
        // then stored plain, which the builder javadoc calls out.
        LlmConfigRepository baseLlmConfigRepository = inMemory
                ? new ai.mindconnect.llm.adapter.memory.InMemoryLlmConfigRepository()
                : new FileLlmConfigRepository(dataDir);
        LlmConfigRepository llmConfigRepository = encryptionKey == null
                ? baseLlmConfigRepository
                : new EncryptingLlmConfigRepository(baseLlmConfigRepository, encryption);
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        var openAi = new OpenAiCompatibleGateway(httpClient, objectMapper, encryption);
        var claude = new ClaudeGateway(httpClient, objectMapper, encryption);
        var azure = new AzureOpenAiGateway(httpClient, objectMapper, encryption);
        var gemini = new GeminiGateway(httpClient, objectMapper, encryption);
        Map<LlmProvider, LlmGateway> gateways = new HashMap<>();
        for (LlmProvider provider : LlmProvider.values()) {
            gateways.put(provider, openAi);   // OpenAI-compatible is the safe default
        }
        gateways.put(LlmProvider.ANTHROPIC, claude);
        gateways.put(LlmProvider.AZURE_OPENAI, azure);
        gateways.put(LlmProvider.GOOGLE_GEMINI, gemini);
        LlmChat llmChat = new RoutingLlmChatService(llmConfigRepository, new DefaultLlmGatewayRegistry(gateways));
        LlmEmbeddings embeddings = new OpenAiEmbeddingsGateway(httpClient, objectMapper, encryption);

        // 4. Prompting, memory, stateless tasks.
        TodoListService todoListService = new TodoListService(todoListRepository);
        TokenCounters tokenCounterRegistry = new TokenCounterRegistry();
        List<PromptContextProvider> promptProviders = List.of(
                new CurrentDateProvider(), new AgentMetadataProvider(),
                new AgentToolsProvider(), new WorkspaceNotesProvider(workspaceStore));
        PromptRenderer promptRenderer = new PebblePromptRenderer(promptProviders);
        Namespace namespace = new Namespace(namespaceName);
        AgentTaskRunner statelessRunner = new StatelessAgentTaskRunner(
                definitionRepository, llmChat, namespace, resolveDefaultLlmConfigName(), promptRenderer);
        ToolResultSummarizer summarizer = "llm".equalsIgnoreCase(toolResultSummarizer)
                ? new LlmToolResultSummarizer(statelessRunner) : new RuleBasedToolResultSummarizer();
        MemoryStrategyFactory memoryStrategyFactory = new DefaultMemoryStrategyFactory(
                conversationManager, summaryRepository, summarizer, statelessRunner,
                tokenCounterRegistry, llmConfigRepository);

        // 5. Tools: SPI over whatever capability modules are on the classpath.
        DynamicToolActivations activations = new DynamicToolActivations(sessionRepository);
        ToolRegistryRef registryRef = new ToolRegistryRef();
        MapToolEnvironment.Builder env = MapToolEnvironment.builder()
                .service(AgentDefinitionRepository.class, definitionRepository)
                .service(AgentSessionRepository.class, sessionRepository)
                .service(MessageRepository.class, messageRepository)
                .service(WorkspaceStore.class, workspaceStore)
                .service(TodoListService.class, todoListService)
                .service(ToolRegistryRef.class, registryRef)
                .service(DynamicToolActivations.class, activations)
                .service(LlmEmbeddings.class, embeddings)
                .service(LlmConfigRepository.class, llmConfigRepository);
        environment.forEach(env::string);
        ToolRegistry toolRegistry = new SpiToolRegistry(env.build());
        registryRef.set(toolRegistry);

        // 6. Turn pipeline + chat service — the turn runs as an agent.turn
        //    task on an in-process queue (concept 16).
        ExecutorService turnExecutor = Executors.newVirtualThreadPerTaskExecutor();
        ToolExecutor toolExecutor = new ToolExecutor(List.of());
        LlmCallTraceRepository traceRepository = inMemory
                ? new InMemoryLlmCallTraceRepository()
                : new ai.mindconnect.agent.adapter.file.FileLlmCallTraceRepository(dataDir);
        var approvalStore = new ai.mindconnect.agent.service.approval.ToolApprovalStore();
        AgentSessionService sessionService = new AgentSessionService(
                definitionRepository, sessionRepository, conversationManager,
                workingMemoryRepository, summaryRepository, todoListRepository, approvalStore);
        var turnChannels = new ai.mindconnect.agent.service.stream.TurnChannels();
        var turnWorker = new ai.mindconnect.agent.service.task.AgentTurnWorker(
                conversationManager, definitionRepository, sessionService,
                memoryStrategyFactory, promptRenderer, toolRegistry, activations,
                llmChat, traceRepository, turnChannels,
                statelessRunner, workingMemoryRepository);
        var toolWorker = new ai.mindconnect.agent.service.task.ToolCallWorker(
                conversationManager, definitionRepository, sessionService,
                memoryStrategyFactory, toolRegistry, activations, toolExecutor, turnChannels,
                approvalStore);
        var taskQueue = new ai.mindconnect.taskqueue.local.LocalTaskQueue(
                new ai.mindconnect.taskqueue.memory.InMemoryTaskStore());
        toolWorker.attach(taskQueue);
        taskQueue.register(ai.mindconnect.agent.service.task.AgentTurnWorker.TYPE, turnWorker);
        taskQueue.register(ai.mindconnect.agent.service.task.ToolCallWorker.TYPE, toolWorker);
        AgentChatService chatService = new AgentChatService(sessionService, definitionRepository,
                conversationManager, memoryStrategyFactory, workingMemoryRepository, promptRenderer,
                statelessRunner, turnChannels, taskQueue, approvalStore, turnExecutor);

        // 7. Seed configs, agents, workflows.
        for (LlmConfig config : pendingLlmConfigs) llmConfigRepository.save(config);
        for (AgentDefinition definition : pendingAgentDefinitions) definitionRepository.save(definition);
        seedWorkflows();

        AttachSupport attachSupport = AttachSupport.createIfPresent(
                environment, activations, sessionRepository, embeddings, llmConfigRepository);
        return new AgentRuntime(chatService, sessionService, definitionRepository,
                llmConfigRepository, conversationManager, namespace, turnExecutor, attachSupport,
                approvalStore);
    }

    private void seedWorkflows() {
        if (pendingWorkflowResources.isEmpty()) {
            return;
        }
        Path workflowDir = Path.of(environment.get("workflowDir"));
        try {
            Files.createDirectories(workflowDir);
            for (String resource : pendingWorkflowResources) {
                String name = Path.of(resource).getFileName().toString();
                try (InputStream in = classpath(resource)) {
                    Files.copy(in, workflowDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not seed workflows into " + workflowDir, e);
        }
    }

    private String resolveDefaultLlmConfigName() {
        if (defaultLlmConfigName != null) return defaultLlmConfigName;
        return pendingLlmConfigs.size() == 1 ? pendingLlmConfigs.get(0).name() : null;
    }

    private <T> T readClasspath(String resource, Class<T> type) {
        try (InputStream in = classpath(resource)) {
            return objectMapper.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read classpath resource: " + resource, e);
        }
    }

    private static InputStream classpath(String resource) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = AgentRuntimeBuilder.class.getClassLoader();
        InputStream in = cl.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalArgumentException("Classpath resource not found: " + resource);
        }
        return in;
    }
}
