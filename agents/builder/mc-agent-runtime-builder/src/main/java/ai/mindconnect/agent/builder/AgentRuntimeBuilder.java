package ai.mindconnect.agent.builder;

import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.memory.strategy.DefaultMemoryStrategyFactory;
import ai.mindconnect.agent.runtime.adapter.filestore.FileStorePartContentReader;
import ai.mindconnect.agent.runtime.adapter.llm.LlmToolResultSummarizer;
import ai.mindconnect.agent.runtime.adapter.prompt.PebblePromptRenderer;
import ai.mindconnect.agent.runtime.adapter.rule.RuleBasedToolResultSummarizer;
import ai.mindconnect.agent.runtime.adapter.token.TokenCounterRegistry;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.feature.BeansToolEnvironment;
import ai.mindconnect.agent.runtime.feature.DefaultFeatureContext;
import ai.mindconnect.agent.runtime.feature.DefaultRuntimeBeans;
import ai.mindconnect.agent.runtime.feature.FeatureException;
import ai.mindconnect.agent.runtime.feature.FeatureRegistry;
import ai.mindconnect.agent.runtime.feature.NamespaceRouting;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.agent.runtime.feature.RuntimeFeature;
import ai.mindconnect.agent.runtime.feature.core.CoreFeature;
import ai.mindconnect.agent.runtime.memory.port.in.MemoryStrategyFactory;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.runtime.port.in.AgentTaskRunner;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.runtime.port.out.LlmMessageMapper;
import ai.mindconnect.agent.runtime.port.out.PartContentReader;
import ai.mindconnect.agent.runtime.port.out.PromptContextProvider;
import ai.mindconnect.agent.runtime.port.out.PromptRenderer;
import ai.mindconnect.agent.runtime.port.out.TokenCounters;
import ai.mindconnect.agent.runtime.port.out.ToolResultSummarizer;
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.runtime.service.MessageToLlmMessageMapper;
import ai.mindconnect.agent.runtime.service.StatelessAgentSeeder;
import ai.mindconnect.agent.runtime.service.StatelessAgentTaskRunner;
import ai.mindconnect.agent.runtime.service.UserHome;
import ai.mindconnect.agent.runtime.service.WorkingDirPolicy;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryToolApprovalRepository;
import ai.mindconnect.agent.runtime.port.out.ToolApprovalRepository;
import ai.mindconnect.agent.runtime.service.prompt.AgentMetadataProvider;
import ai.mindconnect.agent.runtime.service.prompt.AgentToolsProvider;
import ai.mindconnect.agent.runtime.service.prompt.CurrentDateProvider;
import ai.mindconnect.agent.runtime.service.prompt.InstructionFiles;
import ai.mindconnect.agent.runtime.service.prompt.PromptSection;
import ai.mindconnect.agent.runtime.service.prompt.PromptSections;
import ai.mindconnect.agent.runtime.service.stream.SessionChannels;
import ai.mindconnect.agent.runtime.service.stream.UserChannels;
import ai.mindconnect.agent.runtime.service.task.AgentTurnWorker;
import ai.mindconnect.agent.runtime.service.task.SessionTitleWorker;
import ai.mindconnect.agent.runtime.service.task.SubAgentSupport;
import ai.mindconnect.agent.runtime.service.task.ToolCallWorker;
import ai.mindconnect.agent.runtime.service.turn.ToolExecutor;
import ai.mindconnect.agent.runtime.skill.SkillCatalog;
import ai.mindconnect.agent.runtime.tools.todo.TodoListRepository;
import ai.mindconnect.agent.runtime.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.agent.tool.ToolAdvisor;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.message.port.in.ConversationManager;
import ai.mindconnect.taskqueue.LoggingTaskListener;
import ai.mindconnect.taskqueue.TaskAdvisor;
import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Consumer;

/**
 * Assembles an {@link AgentRuntime} without Spring, the way an
 * {@code ObjectMapper} is assembled: a core plus the {@link RuntimeFeature
 * features} installed into it.
 *
 * <pre>{@code
 * try (AgentRuntime runtime = AgentRuntimeBuilder.useFilePersistence(Path.of("./data"))
 *         .llmConfigFromClasspath("llm/openai.json")
 *         .agentDefinitionFromClasspath("agents/demo-agent.json")
 *         .build()) {
 *     System.out.println(runtime.ask("demo-agent", "user", "Hello?", e -> {}));
 * }
 * }</pre>
 *
 * <p>The {@code use…} factories are batteries included: they install every
 * feature module on the classpath ({@link #installFromClasspath()}) — the
 * {@code mc-agent-runtime-feature-*} modules a client puts in its pom are the
 * features its runtime has, each bringing its own capability modules. {@link #of(Persistence)} starts with the smallest runtime that
 * chats — the turn loop with {@link CoreFeature}: LLM, messages, agents — and
 * every further feature is an explicit {@link #install}. A feature installed
 * under the name of one already there replaces it — that is how a configured
 * instance takes the place of a default:
 *
 * <pre>{@code
 * AgentRuntimeBuilder.useInMemoryPersistence()
 *         .install(new CoreFeature().encryptionKey(key).llmConfig(openAi))
 *         .install(new ToolsFeature().disabled("bash"))
 *         .build();
 * }</pre>
 *
 * <p>Settings that are plain strings ({@link #property}) reach every feature
 * and the tools' environment; the named shortcuts below set the ones the
 * shipped features read.
 */
public class AgentRuntimeBuilder {

    private final Persistence persistence;
    private final DefaultRuntimeBeans beans = new DefaultRuntimeBeans();
    private final FeatureRegistry features = new FeatureRegistry();
    private final AgentRuntime runtime = new AgentRuntime(beans, features);
    private final DefaultFeatureContext context;
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private String namespaceName = Namespace.DEFAULT.value();
    private String toolResultSummarizer = "rule";
    private EnvVarResolver envVarResolver = EnvVarResolver.system();
    /** null → the default mapper, reading media parts from the runtime's file store. */
    private LlmMessageMapper llmMessageMapper;
    private java.time.Duration taskRetention = java.time.Duration.ZERO;
    private boolean built;

    private AgentRuntimeBuilder(Persistence persistence) {
        this.persistence = persistence;
        this.context = new DefaultFeatureContext(runtime, beans, persistence, objectMapper);
        runtime.context(context);
        context.property("defaultBaseDir", System.getProperty("user.home"));
        context.property("dataBaseDir", persistence.dataDir().toString());
    }

    // ── starting points ────────────────────────────────────────────────────

    /**
     * The smallest runtime that chats: the turn loop with {@link CoreFeature} —
     * LLM, messages, agents; no tools, no skills. Every further feature is an
     * explicit {@link #install}; the core one is reached with {@link #feature}
     * to configure, or replaced by installing an instance of its class.
     */
    public static AgentRuntimeBuilder of(Persistence persistence) {
        return new AgentRuntimeBuilder(persistence).install(new CoreFeature());
    }

    /** Starts a builder with file persistence rooted at {@code dataDir}, every shipped feature installed. */
    public static AgentRuntimeBuilder useFilePersistence(Path dataDir) {
        return of(Persistence.file(dataDir)).installFromClasspath();
    }

    /**
     * Every repository in Postgres, over the given (ideally pooled) data
     * source; the tables are created on {@link #build()}. {@code dataDir}
     * still roots the file-based side channels — workflows, vector-store
     * files, code-execution scratch — that have no database form.
     */
    public static AgentRuntimeBuilder usePostgres(javax.sql.DataSource dataSource, Path dataDir) {
        return of(Persistence.postgres(dataSource, dataDir)).installFromClasspath();
    }

    /** File persistence under a fresh temp directory (deleted by the OS, not by us). */
    public static AgentRuntimeBuilder useTempPersistence() {
        return useFilePersistence(tempDir());
    }

    /**
     * Purely in-memory persistence — nothing survives {@link AgentRuntime#close()}.
     * The simplest possible setup for tests and short-lived embeddings. File-rooted
     * side channels (vector store files, workflow definitions, code-exec scratch)
     * still use a temp directory when their optional modules are present.
     */
    public static AgentRuntimeBuilder useInMemoryPersistence() {
        return of(Persistence.inMemory(tempDir())).installFromClasspath();
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("mc-agent-runtime");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── features ───────────────────────────────────────────────────────────

    /**
     * Installs a feature. Its {@link RuntimeFeature#dependsOn() dependencies}
     * must already be installed — a missing one is an error here, not an
     * auto-install. A feature of a name already installed replaces it.
     */
    public AgentRuntimeBuilder install(RuntimeFeature feature) {
        requireNotBuilt();
        features.install(feature);
        return this;
    }

    /**
     * Installs every {@link RuntimeFeature} registered as a service
     * ({@code META-INF/services/ai.mindconnect.agent.runtime.feature.RuntimeFeature})
     * on the classpath, sorted by their dependencies — the way Jackson's
     * {@code findAndRegisterModules} finds modules. Features already installed
     * under the same name are kept, so a configured instance wins over a
     * discovered default.
     */
    public AgentRuntimeBuilder installFromClasspath() {
        requireNotBuilt();
        List<RuntimeFeature> found = new ArrayList<>();
        ServiceLoader.load(RuntimeFeature.class).forEach(feature -> {
            if (features.byName(feature.name()).isEmpty()) found.add(feature);
        });
        features.installAll(found);
        return this;
    }

    /** Configures an installed feature in place — a default one, or one installed earlier. */
    public <F extends RuntimeFeature> AgentRuntimeBuilder configure(Class<F> feature, Consumer<F> configurer) {
        requireNotBuilt();
        configurer.accept(features.get(feature));
        return this;
    }

    /** The installed feature of the given class — the same instance {@code runtime.feature(type)} returns after {@link #build()}. */
    public <F extends RuntimeFeature> F feature(Class<F> type) {
        return features.get(type);
    }

    // ── core settings ──────────────────────────────────────────────────────

    /**
     * How long finished task trees (a turn with its tool calls and sub-agent
     * turns) stay readable on the queue. Default {@code Duration.ZERO}: forgotten
     * at the next maintenance tick — the result is in the conversation, and an
     * embedded runtime must not grow with every turn. {@code null} keeps every
     * record for the life of the process, as the queue library does on its own.
     */
    public AgentRuntimeBuilder taskRetention(java.time.Duration keepFinished) {
        requireNotBuilt();
        this.taskRetention = keepFinished;
        // The task-queue feature builds the queue when it is installed, so the setting travels as a
        // property rather than staying a field only the core's own default queue would read.
        return property("taskRetention", keepFinished == null ? KEEP_FOREVER : keepFinished.toString());
    }

    /**
     * Where {@code beans().find(type)} looks when no feature registered the type:
     * a host container's beans, so that a tool from an optional module finds the
     * host's service without any feature naming it.
     */
    public AgentRuntimeBuilder beanFallback(java.util.function.Function<Class<?>, java.util.Optional<?>> fallback) {
        requireNotBuilt();
        beans.fallback(fallback);
        return this;
    }

    /** The mapper the runtime's stores and gateways use — handed to the features' context as well. */
    public AgentRuntimeBuilder objectMapper(ObjectMapper objectMapper) {
        requireNotBuilt();
        this.objectMapper = objectMapper;
        context.objectMapper(objectMapper);
        return this;
    }

    /** The namespace every store of this runtime is bound to (default {@code local}). */
    public AgentRuntimeBuilder namespace(String namespace) {
        this.namespaceName = namespace;
        return this;
    }

    /** LLM config for internal stateless tasks; defaults to the single registered config. */
    public AgentRuntimeBuilder defaultLlmConfigName(String name) {
        return configure(CoreFeature.class, core -> core.defaultLlmConfigName(name));
    }

    public AgentRuntimeBuilder encryptionKey(String secretKey) {
        return configure(CoreFeature.class, core -> core.encryptionKey(secretKey));
    }

    /**
     * Where {@code ${VAR}} placeholders in LLM configs get their values — the
     * process environment unless the host has sources of its own (a per-user
     * store, a vault). Chain them with {@link EnvVarResolver#chain}. Every
     * feature can ask for it: {@code ctx.require(EnvVarResolver.class)}.
     */
    public AgentRuntimeBuilder envVarResolver(EnvVarResolver envVarResolver) {
        this.envVarResolver = java.util.Objects.requireNonNull(envVarResolver, "envVarResolver");
        return this;
    }

    /** {@code rule} (default) or {@code llm}: how oversized tool results are shortened. */
    public AgentRuntimeBuilder toolResultSummarizer(String type) {
        this.toolResultSummarizer = type;
        return this;
    }

    public AgentRuntimeBuilder llmMessageMapper(LlmMessageMapper mapper) {
        this.llmMessageMapper = mapper;
        return this;
    }

    // ── properties (plain strings — reach every feature and the tools) ─────

    public AgentRuntimeBuilder property(String key, String value) {
        requireNotBuilt();
        context.property(key, value);
        return this;
    }

    public AgentRuntimeBuilder toolsBaseDir(Path dir) {
        return property("defaultBaseDir", dir.toString());
    }

    public AgentRuntimeBuilder workingDirRoot(Path root) {
        return property("workingDirRoot", root.toString());
    }

    public AgentRuntimeBuilder usersHome(String template) {
        return property("usersHome", template);
    }

    public AgentRuntimeBuilder disabledTools(String... toolNames) {
        return property("disabledTools", String.join(",", toolNames));
    }

    public AgentRuntimeBuilder workingDirChoice(boolean allowed) {
        return property("workingDirChoice", Boolean.toString(allowed));
    }

    public AgentRuntimeBuilder tavilyApiKey(String key) {
        return property("tavilyApiKey", key);
    }

    public AgentRuntimeBuilder codeExecutionRuntime(String runtime) {
        return property("codeExecRuntime", runtime);
    }

    public AgentRuntimeBuilder vectorStoreBackend(String backend) {
        return property("vectorStoreBackend", backend);
    }

    public AgentRuntimeBuilder embeddingConfigName(String name) {
        return property("vectorStoreEmbeddingConfig", name);
    }

    // ── seeding ────────────────────────────────────────────────────────────

    public AgentRuntimeBuilder llmConfig(LlmConfig config) {
        return configure(CoreFeature.class, core -> core.llmConfig(config));
    }

    public AgentRuntimeBuilder llmConfigFromClasspath(String resource) {
        return llmConfig(readClasspath(resource, LlmConfig.class));
    }

    public AgentRuntimeBuilder agentDefinition(AgentDefinition definition) {
        return configure(CoreFeature.class, core -> core.agentDefinition(definition));
    }

    public AgentRuntimeBuilder agentDefinitionFromClasspath(String resource) {
        return agentDefinition(readClasspath(resource, AgentDefinition.class));
    }

    // Skills and workflows are seeded on their features: builder.feature(SkillsFeature.class).skillFromClasspath(...),
    // builder.feature(WorkflowsFeature.class).seed(...) — those modules are optional, so the builder does not name them.

    // ── build ──────────────────────────────────────────────────────────────

    /**
     * Configures every installed feature in installation order, wires the core
     * around their beans, runs the start hooks and freezes the registry.
     */
    public AgentRuntime build() {
        requireNotBuilt();
        built = true;
        registerCoreSettings();
        for (RuntimeFeature feature : features.all()) {
            feature.configure(context);
        }
        registerCore();
        beans.freeze();
        context.start();
        return runtime;
    }

    /** What every feature may rely on before anything else: the namespace, the mapper, the Sql. */
    private void registerCoreSettings() {
        context.bean(Namespace.class, () -> new Namespace(namespaceName));
        // Where this runtime works — one namespace for its whole life.
        context.bean(ScopeSupplier.class, () -> ScopeSupplier.fixed(context.require(Namespace.class)));
        // … and so every adapter is built once, for that namespace. A feature that binds the
        // scope per call (the namespace feature) replaces both beans.
        context.bean(NamespaceRouting.class, () -> NamespaceRouting.fixed(context.require(Namespace.class)));
        context.bean(ObjectMapper.class, () -> objectMapper);
        context.bean(EnvVarResolver.class, () -> envVarResolver);
        if (persistence instanceof Persistence.Postgres postgres) {
            // One Sql for every Postgres store, around this builder's mapper, so
            // the documents in the database are the JSON the file store writes.
            context.bean(ai.mindconnect.jdbc.Sql.class, () -> ai.mindconnect.jdbc.Sql.of(
                    postgres.dataSource(), new ai.mindconnect.jdbc.Json(objectMapper)));
        }
        context.bean(ToolEnvironment.class, () -> new BeansToolEnvironment(beans, context::properties));
    }

    /**
     * The turn loop, against whatever the features registered. What the loop
     * needs but no feature provided gets a null object here — a runtime
     * without the tools feature offers its agents no tools, and chats.
     */
    private void registerCore() {
        context.bean(TokenCounters.class, TokenCounterRegistry::new);
        context.bean(PromptRenderer.class, () -> {
            List<PromptContextProvider> providers = new ArrayList<>(List.of(
                    new CurrentDateProvider(), new AgentMetadataProvider(), new AgentToolsProvider()));
            providers.addAll(beans.all(PromptContextProvider.class));
            return new PebblePromptRenderer(providers);
        });
        // The sections the features add to every system prompt, in contribution order.
        context.bean(PromptSections.class, () -> PromptSections.of(beans.all(PromptSection.class)));
        context.bean(AgentTaskRunner.class, () -> {
            String defaultConfig = features.find(CoreFeature.class).map(CoreFeature::defaultLlmConfigName).orElse(null);
            var definitions = context.require(AgentDefinitionRepository.class);
            return new StatelessAgentTaskRunner(definitions, context.require(LlmChat.class), defaultConfig,
                    context.require(PromptRenderer.class),
                    new StatelessAgentSeeder(definitions, context.require(LlmConfigRepository.class), defaultConfig));
        });
        context.bean(ToolResultSummarizer.class, () -> "llm".equalsIgnoreCase(toolResultSummarizer)
                ? new LlmToolResultSummarizer(context.require(AgentTaskRunner.class))
                : new RuleBasedToolResultSummarizer());
        context.bean(LlmMessageMapper.class, () -> llmMessageMapper != null ? llmMessageMapper
                : new MessageToLlmMessageMapper(context.find(ai.mindconnect.filestore.FileStore.class)
                        .<PartContentReader>map(FileStorePartContentReader::new)
                        .orElse(PartContentReader.none())));
        context.bean(MemoryStrategyFactory.class, () -> new DefaultMemoryStrategyFactory(
                context.require(ConversationManager.class), context.require(ConversationSummaryRepository.class),
                context.require(ToolResultSummarizer.class), context.require(AgentTaskRunner.class),
                context.require(TokenCounters.class), context.require(LlmConfigRepository.class),
                context.require(LlmMessageMapper.class)));
        if (!beans.has(ToolApprovalRepository.class)) {
            // Open approval questions in memory, unless a feature brings storage that outlives a restart.
            context.bean(ToolApprovalRepository.class, InMemoryToolApprovalRepository::new);
        }
        context.bean(UserChannels.class, UserChannels::new);
        context.bean(SessionChannels.class, SessionChannels::new);

        // Null objects for the features that are not installed.
        if (!beans.has(SkillCatalog.class)) context.bean(SkillCatalog.class, SkillCatalog::none);
        if (!beans.has(ToolRegistry.class)) context.instance(ToolRegistry.class, NO_TOOLS);
        if (!beans.has(SubAgentSupport.class)) context.instance(SubAgentSupport.class, SubAgentSupport.disabled());
        if (!beans.has(DynamicToolActivations.class)) {
            context.bean(DynamicToolActivations.class, () -> new DynamicToolActivations(
                    context.require(AgentSessionRepository.class), context.require(SkillCatalog.class)));
        }
        if (!beans.has(ToolExecutor.class)) {
            context.bean(ToolExecutor.class, () -> new ToolExecutor(beans.all(ToolAdvisor.class)));
        }
        if (!beans.has(TaskQueue.class)) {
            // The turn runs as a task on an in-process queue (concept 16); the task-queue feature replaces it.
            context.bean(TaskQueue.class, () -> {
                var queue = new LocalTaskQueue(new InMemoryTaskStore());
                // A failed task would otherwise leave no trace but a tool result saying so.
                queue.addListener(LoggingTaskListener.failuresOnly());
                // Finished task trees are forgotten at the next maintenance tick unless
                // taskRetention() says otherwise — the turn's outcome lives in the conversation.
                queue.withRetention(taskRetention);
                // Every task carries the scope it was submitted in, is audited, … — whatever the features contributed.
                beans.all(TaskAdvisor.class).forEach(queue::addAdvisor);
                return queue;
            });
        }

        context.bean(AgentSessionService.class, () -> new AgentSessionService(
                context.require(AgentDefinitionRepository.class), context.require(AgentSessionRepository.class),
                context.require(ConversationManager.class), context.require(WorkingMemoryRepository.class),
                context.require(ConversationSummaryRepository.class), context.require(TodoListRepository.class),
                context.require(ToolApprovalRepository.class), context.require(UserChannels.class),
                context.require(WorkingDirPolicy.class), context.require(UserHome.class),
                context.require(LlmCallTraceRepository.class)));
        context.bean(AgentTurnWorker.class, () -> new AgentTurnWorker(
                context.require(ConversationManager.class), context.require(AgentDefinitionRepository.class),
                context.require(AgentSessionService.class), context.require(MemoryStrategyFactory.class),
                context.require(PromptRenderer.class), context.require(ToolRegistry.class),
                context.require(DynamicToolActivations.class), context.require(LlmChat.class),
                context.require(LlmCallTraceRepository.class), context.require(SessionChannels.class),
                context.require(AgentTaskRunner.class), context.require(WorkingMemoryRepository.class),
                context.require(InstructionFiles.class), context.require(SkillCatalog.class),
                context.require(SubAgentSupport.class), context.require(PromptSections.class)));
        context.bean(ToolCallWorker.class, () -> new ToolCallWorker(
                context.require(ConversationManager.class), context.require(AgentDefinitionRepository.class),
                context.require(AgentSessionService.class), context.require(MemoryStrategyFactory.class),
                context.require(ToolRegistry.class), context.require(DynamicToolActivations.class),
                context.require(ToolExecutor.class), context.require(SessionChannels.class),
                context.require(ToolApprovalRepository.class), context.require(UserChannels.class),
                context.require(SubAgentSupport.class)));
        context.bean(SessionTitleWorker.class, () -> new SessionTitleWorker(
                context.require(AgentSessionService.class), context.require(ConversationManager.class),
                context.require(AgentTaskRunner.class), context.require(UserChannels.class)));
        context.bean(AgentChatService.class, () -> new AgentChatService(
                context.require(AgentSessionService.class), context.require(AgentDefinitionRepository.class),
                context.require(ConversationManager.class), context.require(MemoryStrategyFactory.class),
                context.require(WorkingMemoryRepository.class), context.require(PromptRenderer.class),
                context.require(SessionChannels.class), context.require(UserChannels.class),
                context.require(TaskQueue.class), context.require(ToolApprovalRepository.class),
                context.require(InstructionFiles.class), context.require(SkillCatalog.class),
                context.require(ScopeSupplier.class), context.require(PromptSections.class)));

        // The features' start hooks (schema, seeds) ran before this one: hooks run in registration order.
        context.onStart(() -> {
            TaskQueue queue = context.require(TaskQueue.class);
            context.require(ToolCallWorker.class).attach(queue);
            queue.register(AgentTurnWorker.TYPE, context.require(AgentTurnWorker.class));
            queue.register(ToolCallWorker.TYPE, context.require(ToolCallWorker.class));
            // A chat is named by a task of its own; the turn handle waits for it.
            queue.register(SessionTitleWorker.TYPE, context.require(SessionTitleWorker.class));
            context.require(AgentChatService.class);
        });
        // Whoever registered the queue, the runtime that built it closes it — and only if it was built.
        context.onClose(() -> {
            if (beans.ifBuilt(TaskQueue.class).orElse(null) instanceof AutoCloseable closeable) closeable.close();
        });
    }

    /** A registry that knows no tool: every resolve is empty, every listing blank. */
    private static final ToolRegistry NO_TOOLS = (agentTool, scope) -> Optional.empty();

    /** Value of the {@code taskRetention} property that means "keep finished task trees for the life of the process". */
    public static final String KEEP_FOREVER = "keep";

    private void requireNotBuilt() {
        if (built) {
            throw new FeatureException("The runtime is built; the builder cannot be changed any more");
        }
    }

    private <T> T readClasspath(String resource, Class<T> type) {
        try (InputStream in = classpath(resource)) {
            return objectMapper.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read classpath resource: " + resource, e);
        }
    }

    static InputStream classpath(String resource) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = AgentRuntimeBuilder.class.getClassLoader();
        InputStream in = cl.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalArgumentException("Classpath resource not found: " + resource);
        }
        return in;
    }
}
