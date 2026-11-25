package ai.mindconnect.agent.starter.runtime.engine;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.Environment;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.jackson.JacksonPlugin;
import ai.mindconnect.jackson.JsonMapperCustomizer;
import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.migration.AppliedMigration;
import ai.mindconnect.agent.migration.plugin.FileMigrationPlugin;
import ai.mindconnect.agent.migration.plugin.MigrationPlugin;
import ai.mindconnect.agent.migration.MigrationRunner;
import ai.mindconnect.agent.migration.pg.PgMigrationPlugin;
import ai.mindconnect.agent.runtime.store.SessionMessageMigration;
import ai.mindconnect.agent.runtime.store.SessionMessages;
import ai.mindconnect.agent.scope.Scope;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.management.ManagementPlugin;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.management.api.SessionCleanup;
import ai.mindconnect.agent.management.directory.DirectoryPlugin;
import ai.mindconnect.agent.management.directory.SessionDirectoryCleanup;
import ai.mindconnect.agent.management.directory.UserHome;
import ai.mindconnect.agent.management.directory.WorkingDirBrowser;
import ai.mindconnect.agent.management.directory.WorkingDirPolicy;
import ai.mindconnect.agent.runtime.api.domain.BackgroundModel;
import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.capabilities.attachment.AttachmentPlugin;
import ai.mindconnect.agent.runtime.capabilities.memory.MemoryCompaction;
import ai.mindconnect.agent.runtime.capabilities.memory.MemoryPlugin;
import ai.mindconnect.agent.runtime.capabilities.memory.SessionSummaryStore;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionEffect;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionPlugin;
import ai.mindconnect.agent.runtime.capabilities.permission.SessionApprovalMemory;
import ai.mindconnect.agent.runtime.capabilities.prompt.PromptPlugin;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import ai.mindconnect.agent.runtime.capabilities.toolresult.ToolResultPlugin;
import ai.mindconnect.agent.runtime.capabilities.toolsearch.SessionToolActivation;
import ai.mindconnect.agent.runtime.capabilities.toolsearch.ToolActivation;
import ai.mindconnect.agent.runtime.capabilities.toolsearch.ToolSearchPlugin;
import ai.mindconnect.agent.runtime.infrastructure.FilePlugin;
import ai.mindconnect.agent.runtime.infrastructure.FileStorePlugin;
import ai.mindconnect.agent.runtime.infrastructure.InMemoryStorePlugin;
import ai.mindconnect.agent.runtime.infrastructure.LlmPlugin;
import ai.mindconnect.agent.runtime.infrastructure.ProjectAgentPlugin;
import ai.mindconnect.agent.runtime.infrastructure.SkillSourcePlugin;
import ai.mindconnect.agent.runtime.infrastructure.StorePlugin;
import ai.mindconnect.agent.runtime.infrastructure.UserSkillDirectory;
import ai.mindconnect.agent.runtime.infrastructure.TokenizerPlugin;
import ai.mindconnect.agent.runtime.infrastructure.ToolRegistryPlugin;
import ai.mindconnect.agent.runtime.infrastructure.pg.PostgresStorePlugin;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.agent.skill.port.out.SkillRepository;
import ai.mindconnect.agent.tool.TimeZones;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.tool.ToolRepository;
import ai.mindconnect.agent.tools.todo.TodoListService;
import ai.mindconnect.agent.tools.todo.TodoPlugin;
import ai.mindconnect.agent.tools.usermemory.UserMemoryPlugin;
import ai.mindconnect.agent.tools.usermemory.UserMemoryService;
import ai.mindconnect.agent.trace.port.out.LlmCallTraceRepository;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import ai.mindconnect.llm.port.in.LlmTranscription;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmPriceRepository;
import ai.mindconnect.llm.service.RoutingLlmChatService;
import ai.mindconnect.message.port.in.ConversationManager;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.namespace.service.NamespaceFirstUse;
import ai.mindconnect.namespace.service.StartupScope;
import ai.mindconnect.namespace.service.ThreadBoundScopeSupplier;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.SettingSource;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.taskqueue.LoggingTaskListener;
import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.TaskStore;
import ai.mindconnect.taskqueue.jdbc.JdbcTaskStore;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;
import ai.mindconnect.vectorstore.tools.VectorStores;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;

/**
 * Builds the one {@link AgentRuntime} of the application from its settings
 * and hands out what the application's own beans inject: each export below
 * asks the runtime for a service and adds nothing.
 *
 * <p>What the runtime is made of is said once, in {@link #agentRuntime}:
 * the stores by {@code mindconnect.persistence} ({@code file}, the default,
 * {@code postgres} or {@code memory}), the models, the tools, the files, the
 * directories, the users' channels. Every {@link Plugin} bean of the
 * application is installed after them. The settings keep their keys:
 * {@code mindconnect.tools.*}, {@code mindconnect.agent.*},
 * {@code mindconnect.vector-store.*}, {@code mindconnect.task-queue.*}.
 */
@AutoConfiguration(
        afterName = {
                "ai.mindconnect.agent.starter.namespace.NamespaceAutoConfiguration",
                "ai.mindconnect.agent.starter.file.FilePersistenceAutoConfiguration",
                "ai.mindconnect.agent.starter.postgres.PostgresPersistenceConfig"},
        beforeName = {
                // Spring Boot makes an ObjectMapper of its own unless there is one: the runtime's is.
                "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration",
                "ai.mindconnect.agent.starter.registry.RegistryAutoConfiguration",
                "ai.mindconnect.agent.starter.workflow.NamespacedWorkflowStoresAutoConfiguration",
                "ai.mindconnect.agent.starter.workflow.McAgentWorkflowStepsAutoConfiguration",
                "ai.mindconnect.mcp.gateway.local.McpGatewayAutoConfiguration"})
public class RuntimeEngineAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RuntimeEngineAutoConfiguration.class);

    /**
     * The scope the runtime works in, for an application without the
     * namespace starter: one namespace, nobody acting.
     */
    @Bean
    @ConditionalOnMissingBean(ScopeSupplier.class)
    ScopeSupplier scopeSupplier(Environment env) {
        return ScopeSupplier.fixed(Scope.of(new Namespace(env.getProperty("mindconnect.namespace", "local"))));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(AgentRuntime.class)
    AgentRuntime agentRuntime(Environment env, ApplicationContext context, ScopeSupplier scope,
                              ObjectProvider<DataSource> dataSource, ObjectProvider<Plugin> more,
                              ObjectProvider<JsonMapperCustomizer> json) {
        Path dataDir = Path.of(env.getProperty("mindconnect.data.base-dir", "data"));
        String persistence = env.getProperty("mindconnect.persistence", "file");
        int maxTraces = env.getProperty("mindconnect.agent.trace.max-per-session", Integer.class, 50);
        StorePlugin stores = switch (persistence) {
            case "postgres" -> new PostgresStorePlugin(dataSource.getObject(), dataDir, maxTraces,
                    "postgres".equals(env.getProperty("mindconnect.file-store.backend", "postgres")));
            case "memory" -> new InMemoryStorePlugin(dataDir, maxTraces);
            default -> new FileStorePlugin(dataDir, maxTraces);
        };
        // A plugin's setting is the application's property of that name under mindconnect.
        SettingSource settings = key -> Optional.ofNullable(env.getProperty("mindconnect." + key));
        // What a capability keeps with the session asks the runtime's session store, once the runtime is built.
        AtomicReference<AgentRuntime> built = new AtomicReference<>();
        Supplier<SessionStore> sessions = () -> built.get().service(SessionStore.class);
        // The users' zones where a plugin keeps users, the JVM's for everybody where none does.
        Supplier<TimeZones> zones = () -> built.get().find(TimeZones.class).orElseGet(TimeZones::system);
        ToolActivation activation = new SessionToolActivation(sessions);

        var builder = AgentRuntime.builder()
                .scope(scope)
                // Made from the runtime's services: a store in the database writes its tasks with the runtime's mapper.
                .taskQueue(registry -> taskQueue(env, dataSource, registry.get(JsonMapperFactory.class)))
                // The application's JSON, as its customizers say; Spring's ObjectMapper is this one.
                .plugin(new JacksonPlugin(json.orderedStream().toList()))
                .plugin(provides("scope", registry -> {
                    registry.provide(SettingSource.class, () -> settings);
                    registry.provide(ToolActivation.class, () -> activation);
                    // The model of what the runtime does on its own account: a title, a summary.
                    Optional.ofNullable(blankToNull(env.getProperty("mindconnect.agent.stateless.llm-config-name")))
                            .ifPresent(name -> registry.provide(BackgroundModel.class,
                                    () -> BackgroundModel.named(name)));
                }))
                // Before the stores: a Postgres installation keeps uploads in the database unless
                // another file store is named, and the store plugin's answer is the later one.
                .plugin(new FilePlugin(env.getProperty("mindconnect.file-store.backend", "filesystem"), dataDir))
                .plugin(stores)
                // The migrations, with their log where the stores are; run per namespace once the runtime is up.
                .plugin(new MigrationPlugin())
                .plugin(migrationPlace(persistence, dataDir))
                // A resolver of the application's own wins; else the sources the plugins contribute.
                .plugin(new LlmPlugin(blankToNull(env.getProperty("mindconnect.encryption.secret-key")),
                        (EnvVarResolver) hostBean(context, EnvVarResolver.class).orElse(null)))
                .plugin(new ToolRegistryPlugin(toolSettings(env, dataDir, persistence),
                        blankToNull(env.getProperty("mindconnect.tools.disabled")), type -> hostBean(context, type)))
                .plugin(new DirectoryPlugin(dataDir, blankToNull(env.getProperty("mindconnect.users.home")),
                        blankToNull(env.getProperty("mindconnect.tools.working-dir-root")),
                        env.getProperty("mindconnect.working-dirs.choice", Boolean.class, true),
                        env.getProperty("mindconnect.agent.instructions.user-dir", "")))
                .plugin(new SkillSourcePlugin(
                        UserSkillDirectory.of(env.getProperty("mindconnect.agent.skills.user-dir", ""))))
                .plugin(new ProjectAgentPlugin())
                .plugin(new ManagementPlugin())
                .plugin(new AttachmentPlugin())
                .plugin(new ToolResultPlugin())
                .plugin(new TodoPlugin())
                .plugin(new UserMemoryPlugin())
                // In place of the builder's own: what a session keeps, it keeps in the session.
                .plugin(new ToolSearchPlugin(activation))
                // A sub-agent works where its caller works; what is kept of the caller's session alone —
                // its conversation, its title, its attachments — stays the caller's.
                .plugin(new SubAgentPlugin(Set.of(ManagedSession.WORKING_DIRECTORY,
                        ManagedSession.ADDITIONAL_DIRECTORIES)::contains,
                        env.getProperty("mindconnect.agent.sub-agents.max-depth", Integer.class,
                                SubAgentPlugin.DEFAULT_MAX_DEPTH)))
                .plugin(new TokenizerPlugin())
                .plugin(new PermissionPlugin(PermissionEffect.DENY, new SessionApprovalMemory(sessions)))
                .plugin(new MemoryPlugin(new SessionSummaryStore(sessions)))
                .plugin(new PromptPlugin(Clock.systemUTC(), user -> zones.get().zoneOf(user)));
        // Off unless the installation says so: deleting a chat must not take files with it.
        if (env.getProperty("mindconnect.sessions.delete-directory", Boolean.class, false)) {
            builder.plugin(provides("session-directory-cleanup", registry -> registry.contribute(
                    SessionCleanup.class,
                    session -> new SessionDirectoryCleanup(registry.get(UserHome.class)).deleted(session))));
        }
        more.orderedStream().forEach(builder::plugin);

        // Start-up work — schema, the first look at the stores — runs in the default namespace;
        // a strict scope would otherwise refuse the unbound main thread.
        Namespace startup = new Namespace(env.getProperty("mindconnect.namespace", "local"));
        AgentRuntime runtime = StartupScope.call(scope, startup, builder::build);
        built.set(runtime);
        prepareNamespaces(runtime, scope, startup,
                env.getProperty("mindconnect.migrations.enabled", Boolean.class, true),
                SessionMessages.of(settings) == SessionMessages.OWN);
        log.info("Agent runtime built: persistence {}, namespace {}", persistence,
                scope instanceof ThreadBoundScopeSupplier ? "per call" : startup.value());
        return runtime;
    }

    /**
     * Brings every namespace's data up to this version the first time the
     * namespace is used after start — a namespace bound for the first time
     * waits for it — or at once where there is one namespace only: the
     * migrations it has not had, then, where a session's messages are kept in
     * their own repository, the move of those still kept as conversations.
     */
    private static void prepareNamespaces(AgentRuntime runtime, ScopeSupplier scope, Namespace startup,
                                          boolean migrate, boolean moveMessages) {
        if (!migrate && !moveMessages) {
            return;
        }
        Consumer<Namespace> prepare = namespace -> {
            if (migrate) {
                MigrationRunner migrations = runtime.service(MigrationRunner.class);
                for (AppliedMigration applied : migrations.run()) {
                    log.info("Migration {} ({}) in namespace {}: {}", applied.id(), applied.version(),
                            namespace.value(), applied.report());
                }
                log.info("Data of namespace {} at version {}", namespace.value(),
                        migrations.dataVersion().orElse("(none migrated)"));
            }
            if (moveMessages) {
                SessionMessageMigration.Report report = runtime.service(SessionMessageMigration.class).run();
                log.info("Session messages of namespace {}: {} sessions moved, {} moved before",
                        namespace.value(), report.migrated().size(), report.skipped().size());
            }
        };
        if (scope instanceof ThreadBoundScopeSupplier bound) {
            bound.onBind(new NamespaceFirstUse("Migrating data", prepare));
        } else {
            StartupScope.run(scope, startup, () -> prepare.accept(startup));
        }
    }

    /** Where the migrations keep their log and find the earlier runtime's data: where the stores are. */
    private static Plugin migrationPlace(String persistence, Path dataDir) {
        return switch (persistence) {
            case "postgres" -> new PgMigrationPlugin();
            // Nothing is kept: the log lasts as long as the process, and there is no earlier data.
            case "memory" -> provides("migrations-memory", registry -> { });
            default -> new FileMigrationPlugin(dataDir);
        };
    }

    /** The tools bind their providers once the context is up — not while it is still refreshing. */
    @Bean
    ApplicationListener<ContextRefreshedEvent> toolWarmUp(AgentRuntime runtime, ScopeSupplier scope, Environment env) {
        Namespace startup = new Namespace(env.getProperty("mindconnect.namespace", "local"));
        return event -> StartupScope.run(scope, startup, () -> runtime.service(ToolRegistryPlugin.class).warmUp());
    }

    /**
     * Stores what an earlier encryption scheme wrote in the current one, in every namespace, once the
     * context is up. Without an encryption key nothing is stored encrypted, and nothing is rewritten.
     */
    @Bean
    ApplicationListener<ContextRefreshedEvent> storedSecretRewrite(
            ObjectProvider<EncryptionHelper> encryption, ObjectProvider<NamespaceService> namespaces,
            LlmConfigRepository llmConfigs, VectorStores vectorStores, ScopeSupplier scope, Environment env) {
        Namespace startup = new Namespace(env.getProperty("mindconnect.namespace", "local"));
        return event -> encryption.ifAvailable(key -> {
            NamespaceService service = namespaces.getIfAvailable();
            new StoredSecretRewrite(key, llmConfigs, vectorStores, scope).rewriteIn(service == null
                    ? List.of(startup)
                    : service.all().stream().map(NamespaceDefinition::id).toList());
        });
    }

    /**
     * The queue: in memory, finished tasks kept for the task monitor (retention
     * unset = keep) — unless {@code mindconnect.task-queue.*} says otherwise.
     */
    private static LocalTaskQueue taskQueue(Environment env, ObjectProvider<DataSource> dataSource,
                                            JsonMapperFactory json) {
        TaskStore store = "jdbc".equalsIgnoreCase(env.getProperty("mindconnect.task-queue.store", "memory"))
                ? new JdbcTaskStore(dataSource.getObject(),
                        Optional.ofNullable(blankToNull(env.getProperty("mindconnect.task-queue.node-id")))
                                .orElseGet(RuntimeEngineAutoConfiguration::defaultNodeId),
                        env.getProperty("mindconnect.task-queue.lease", Duration.class, Duration.ofSeconds(30)), json)
                        .initSchema()
                : new InMemoryTaskStore();
        LocalTaskQueue queue = new LocalTaskQueue(store);
        queue.addListener(LoggingTaskListener.failuresOnly());
        queue.withRetention(env.getProperty("mindconnect.task-queue.retention", Duration.class));
        Optional.ofNullable(env.getProperty("mindconnect.task-queue.maintenance-interval", Duration.class))
                .ifPresent(queue::withMaintenanceInterval);
        return queue;
    }

    private static String defaultNodeId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            host = "node";
        }
        return host + "-" + ProcessHandle.current().pid();
    }

    /** What a tool reads by key, from the same properties the application always read. */
    private static Map<String, String> toolSettings(Environment env, Path dataDir, String persistence) {
        Map<String, String> settings = new HashMap<>();
        settings.put("dataBaseDir", dataDir.toString());
        settings.put("defaultBaseDir", env.getProperty("mindconnect.tools.base-dir", System.getProperty("user.home")));
        settings.put("tavilyApiKey", env.getProperty("mindconnect.tools.tavily-api-key", ""));
        settings.put("codeExecRuntime", env.getProperty("mindconnect.code-exec.runtime", "auto"));
        settings.put("codeExecNetwork", env.getProperty("mindconnect.code-exec.network", "none"));
        settings.put("codeExecLanguages", env.getProperty("mindconnect.code-exec.languages", ""));
        settings.put("codeExecMemory", env.getProperty("mindconnect.code-exec.memory", "512m"));
        settings.put("codeExecCpus", env.getProperty("mindconnect.code-exec.cpus", "1"));
        settings.put("codeExecTimeoutSeconds", env.getProperty("mindconnect.code-exec.timeout-seconds", "60"));
        settings.put("codeExecIdleSeconds", env.getProperty("mindconnect.code-exec.idle-seconds", "600"));
        // Unset: memory — or, under Postgres persistence, pgvector when the database has it.
        settings.put("vectorStoreBackend", env.getProperty("mindconnect.vector-store.backend", ""));
        settings.put("vectorStoreUrl", env.getProperty("mindconnect.vector-store.url", ""));
        settings.put("vectorStoreUser", env.getProperty("mindconnect.vector-store.user", ""));
        settings.put("vectorStorePassword", env.getProperty("mindconnect.vector-store.password", ""));
        settings.put("vectorStoreEmbeddingConfig",
                env.getProperty("mindconnect.vector-store.embedding-config", "embeddings"));
        settings.put("fileStoreBackend", env.getProperty("mindconnect.file-store.backend",
                "postgres".equals(persistence) ? "postgres" : "filesystem"));
        return settings;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Plugin provides(String name, java.util.function.Consumer<PluginRegistry> install) {
        return new Plugin() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void install(PluginRegistry registry) {
                install.accept(registry);
            }
        };
    }

    /**
     * A bean of the host for a type the runtime has no service of — never one
     * of the exports below. Those hand the runtime's own services to the
     * application, so resolving one while the runtime is still being built
     * asks Spring for the runtime again: a cycle that stops the application
     * from starting.
     */
    static Optional<?> hostBean(ApplicationContext context, Class<?> type) {
        if (!(context.getAutowireCapableBeanFactory() instanceof ConfigurableListableBeanFactory factory)) {
            return Optional.ofNullable(context.getBeanProvider(type).getIfUnique());
        }
        List<String> hosts = Arrays.stream(factory.getBeanNamesForType(type, true, false))
                .filter(name -> !exportedHere(factory, name))
                .toList();
        if (hosts.size() > 1) {
            // Several: the primary one, as getIfUnique would pick.
            hosts = hosts.stream()
                    .filter(name -> factory.containsBeanDefinition(name) && factory.getBeanDefinition(name).isPrimary())
                    .toList();
        }
        return hosts.size() == 1 ? Optional.of(factory.getBean(hosts.get(0), type)) : Optional.empty();
    }

    /** Whether {@code name} is one of this configuration's exports of the runtime's services. */
    private static boolean exportedHere(ConfigurableListableBeanFactory factory, String name) {
        if (!factory.containsBeanDefinition(name)) {
            return false;
        }
        return factory.getBeanDefinition(name) instanceof AnnotatedBeanDefinition annotated
                && annotated.getFactoryMethodMetadata() != null
                && RuntimeEngineAutoConfiguration.class.getName()
                        .equals(annotated.getFactoryMethodMetadata().getDeclaringClassName());
    }

    // ── the runtime's services, for the application's own beans to inject ───

    /** The runtime's mapper: built from the modules its plugins contribute, as the application's customizers say. */
    @Bean ObjectMapper objectMapper(AgentRuntime r) { return r.service(ObjectMapper.class); }
    @Bean JsonMapperFactory jsonMapperFactory(AgentRuntime r) { return r.service(JsonMapperFactory.class); }
    /** Where a {@code ${VAR}} resolves: the users' own values, the namespace's, the process environment. */
    @Bean @ConditionalOnMissingBean(EnvVarResolver.class)
    EnvVarResolver envVarResolver(AgentRuntime r) { return r.service(EnvVarResolver.class); }
    /** The database every Postgres store works on, around the runtime's mapper. */
    @Bean @ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "postgres")
    Sql mindconnectSql(AgentRuntime r) { return r.service(Sql.class); }
    @Bean SessionService runtimeSessionService(AgentRuntime r) { return r.sessions(); }
    @Bean TurnService runtimeTurnService(AgentRuntime r) { return r.turns(); }
    @Bean MemoryCompaction memoryCompaction(AgentRuntime r) { return r.service(MemoryCompaction.class); }
    @Bean AgentManagement agentManagement(AgentRuntime r) { return r.service(AgentManagement.class); }
    @Bean SessionManagement sessionManagement(AgentRuntime r) { return r.service(SessionManagement.class); }
    @Bean ToolActivation toolActivation(AgentRuntime r) { return r.service(ToolActivation.class); }
    @Bean WorkingDirBrowser workingDirBrowser(AgentRuntime r) { return r.service(WorkingDirBrowser.class); }
    @Bean WorkingDirPolicy workingDirPolicy(AgentRuntime r) { return r.service(WorkingDirPolicy.class); }
    @Bean UserHome userHome(AgentRuntime r) { return r.service(UserHome.class); }
    @Bean TodoListService todoListService(AgentRuntime r) { return r.service(TodoListService.class); }
    @Bean UserMemoryService userMemoryService(AgentRuntime r) { return r.service(UserMemoryService.class); }
    @Bean SkillRepository skillRepository(AgentRuntime r) { return r.service(SkillRepository.class); }
    @Bean UserSkillDirectory userSkillDirectory(AgentRuntime r) { return r.service(UserSkillDirectory.class); }
    @Bean LlmCallTraceRepository llmCallTraceRepository(AgentRuntime r) { return r.service(LlmCallTraceRepository.class); }
    @Bean ToolRegistry toolRegistry(AgentRuntime r) { return r.service(ToolRegistry.class); }
    @Bean LlmConfigRepository llmConfigRepository(AgentRuntime r) { return r.service(LlmConfigRepository.class); }
    @Bean LlmPriceRepository llmPriceRepository(AgentRuntime r) { return r.service(LlmPriceRepository.class); }
    @Bean LlmEmbeddings llmEmbeddings(AgentRuntime r) { return r.service(LlmEmbeddings.class); }
    @Bean LlmTranscription llmTranscription(AgentRuntime r) { return r.service(LlmTranscription.class); }
    /** The routing chat by its class too: the config test service asks for the concrete type. */
    @Bean RoutingLlmChatService llmChat(AgentRuntime r) { return r.service(RoutingLlmChatService.class); }
    @Bean MessageRepository messageRepository(AgentRuntime r) { return r.service(MessageRepository.class); }
    @Bean ConversationManager conversationManager(AgentRuntime r) { return r.service(ConversationManager.class); }
    /** The queue by its class too: the task monitor reads the local queue's records. Closed with the context. */
    @Bean(destroyMethod = "close") LocalTaskQueue taskQueue(AgentRuntime r) { return (LocalTaskQueue) r.service(TaskQueue.class); }
    @Bean FileStore fileStore(AgentRuntime r) { return r.service(FileStore.class); }
    @Bean VectorStores vectorStores(AgentRuntime r) { return r.service(VectorStores.class); }
    @Bean WorkflowDataRepository workflowDataRepository(AgentRuntime r) { return r.service(WorkflowDataRepository.class); }
    @Bean WorkflowInstanceRepository workflowInstanceRepository(AgentRuntime r) { return r.service(WorkflowInstanceRepository.class); }

    /** The operator's tool settings — on files; a Postgres installation has none yet. */
    @Bean
    @ConditionalOnMissingBean(ToolRepository.class)
    ToolRepository toolRepository(AgentRuntime r) {
        return r.find(ToolRepository.class).orElse(null);
    }
}
