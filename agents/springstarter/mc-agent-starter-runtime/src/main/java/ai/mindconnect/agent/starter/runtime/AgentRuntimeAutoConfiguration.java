package ai.mindconnect.agent.starter.runtime;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.StartupScope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.ThreadBoundScope;
import ai.mindconnect.agent.builder.AgentRuntime;
import ai.mindconnect.agent.builder.AgentRuntimeBuilder;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.agent.runtime.feature.RuntimeFeature;
import ai.mindconnect.agent.runtime.feature.core.CoreFeature;
import ai.mindconnect.agent.runtime.feature.fileupload.FileUploadFeature;
import ai.mindconnect.agent.runtime.feature.namespace.NamespaceFeature;
import ai.mindconnect.agent.runtime.feature.skills.SkillsFeature;
import ai.mindconnect.agent.runtime.feature.subagents.SubAgentsFeature;
import ai.mindconnect.agent.runtime.feature.taskqueue.TaskQueueFeature;
import ai.mindconnect.agent.runtime.feature.tools.ToolsFeature;
import ai.mindconnect.agent.runtime.feature.transcription.TranscriptionFeature;
import ai.mindconnect.agent.runtime.feature.workflows.WorkflowsFeature;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.runtime.port.in.AgentTaskRunner;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.runtime.port.out.PromptRenderer;
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.agent.runtime.service.AgentRegistryService;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.runtime.service.UserHome;
import ai.mindconnect.agent.runtime.service.WorkingDirBrowser;
import ai.mindconnect.agent.runtime.service.WorkingDirPolicy;
import ai.mindconnect.agent.runtime.port.out.ToolApprovalRepository;
import ai.mindconnect.agent.runtime.service.prompt.InstructionFiles;
import ai.mindconnect.agent.runtime.service.stream.SessionChannels;
import ai.mindconnect.agent.runtime.service.stream.UserChannels;
import ai.mindconnect.agent.runtime.skill.SkillCatalog;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.agent.runtime.tools.todo.TodoListRepository;
import ai.mindconnect.agent.runtime.tools.todo.TodoListService;
import ai.mindconnect.agent.runtime.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.tool.ToolRepository;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import ai.mindconnect.llm.port.in.LlmTranscription;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmPriceRepository;
import ai.mindconnect.llm.service.RoutingLlmChatService;
import ai.mindconnect.message.port.in.ConversationManager;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.vectorstore.tools.VectorStores;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.mindconnect.common.env.EnvVarResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Builds the {@link AgentRuntime} for a Spring Boot application the way the
 * embedded builder does — the core plus installed features — and exports its
 * beans, so that controllers and services inject {@link AgentChatService},
 * {@link LlmConfigRepository}, {@link ToolRegistry}, … as before.
 *
 * <p>What the application contributes: a {@link Persistence} (from its
 * persistence starter), the {@link ThreadBoundScope} its request filter binds
 * (from the namespace or persistence starter — then the runtime works in the
 * namespace of the call), its {@link ObjectMapper}, any {@link RuntimeFeature}
 * beans (installed, replacing a shipped feature of the same name) and any
 * {@link AgentRuntimeCustomizer}. Every bean of the context is visible to the
 * runtime's tools as a fallback, so a tool from an optional module finds the
 * host's service — the MCP gateway, say — without the runtime naming it.
 *
 * <p>The settings keep their keys: {@code mindconnect.tools.*},
 * {@code mindconnect.agent.*}, {@code mindconnect.vector-store.*}, … read here
 * and handed to the features.
 */
@AutoConfiguration(
        afterName = {
                "ai.mindconnect.agent.starter.namespace.NamespaceAutoConfiguration",
                "ai.mindconnect.agent.starter.file.FilePersistenceAutoConfiguration",
                "ai.mindconnect.agent.starter.postgres.PostgresPersistenceConfig"},
        beforeName = {
                "ai.mindconnect.agent.registry.spring.RegistryAutoConfiguration",
                "ai.mindconnect.agent.tools.workflow.NamespacedWorkflowStoresAutoConfiguration",
                "ai.mindconnect.agent.tools.workflow.McAgentWorkflowStepsAutoConfiguration",
                "ai.mindconnect.agent.tools.workflow.registry.WorkflowRegistryAutoConfiguration",
                "ai.mindconnect.mcp.gateway.local.McpGatewayAutoConfiguration"})
public class AgentRuntimeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeAutoConfiguration.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(AgentRuntime.class)
    AgentRuntime agentRuntime(Persistence persistence,
                              ObjectMapper objectMapper,
                              Environment env,
                              ApplicationContext context,
                              ObjectProvider<ThreadBoundScope> boundScope,
                              ObjectProvider<EnvVarResolver> envVarResolver,
                              ObjectProvider<RuntimeFeature> features,
                              ObjectProvider<AgentRuntimeCustomizer> customizers) {
        String namespace = env.getProperty("mindconnect.namespace", "local");
        AgentRuntimeBuilder builder = AgentRuntimeBuilder.of(persistence)
                .objectMapper(objectMapper)
                .namespace(namespace)
                .toolResultSummarizer(env.getProperty("mindconnect.agent.tool-result.summarizer", "rule"))
                // A tool from an optional module asks for the host's beans when no feature registered the type.
                .beanFallback(type -> hostBean(context, type));
        // Where ${VAR} placeholders resolve: the namespace starter's chain — the user's own
        // variables, then the namespace's, then the process — or the process alone without it.
        builder.envVarResolver(envVarResolver.getIfAvailable(EnvVarResolver::system));

        CoreFeature core = builder.feature(CoreFeature.class)
                .encryptionKey(blankToNull(env.getProperty("mindconnect.encryption.secret-key")))
                .defaultLlmConfigName(blankToNull(env.getProperty("mindconnect.agent.stateless.llm-config-name")))
                .maxTracesPerConversation(env.getProperty("mindconnect.agent.trace.max-per-session", Integer.class, 50))
                .workingDirChoice(env.getProperty("mindconnect.working-dirs.choice", Boolean.class, true));
        Optional.ofNullable(blankToNull(env.getProperty("mindconnect.users.home"))).ifPresent(core::usersHome);
        Optional.ofNullable(blankToNull(env.getProperty("mindconnect.tools.working-dir-root")))
                .ifPresent(root -> core.workingDirRoot(Path.of(root)));
        Optional.ofNullable(blankToNull(env.getProperty("mindconnect.agent.instructions.user-dir"))).ifPresent(core::instructionsUserDir);

        // The shipped features, configured from the same properties the Spring runtime always read.
        builder.install(new SkillsFeature().userDir(env.getProperty("mindconnect.agent.skills.user-dir", "")));
        ToolsFeature tools = new ToolsFeature().deferred();   // bound once the context is up, see below
        String disabled = env.getProperty("mindconnect.tools.disabled", "");
        if (!disabled.isBlank()) tools.disabled(disabled.split("\\s*,\\s*"));
        builder.install(tools)
                .install(new WorkflowsFeature())
                .install(new FileUploadFeature())
                .install(new TranscriptionFeature())   // the chat's voice input
                .install(new SubAgentsFeature().maxDepth(env.getProperty("mindconnect.agent.sub-agents.max-depth", Integer.class, 5)));
        // The queue: as the Spring runtime always ran it — in memory, finished tasks kept for the task
        // monitor (retention unset = keep) — unless mindconnect.task-queue.* says otherwise.
        TaskQueueFeature queue = new TaskQueueFeature()
                .retention(env.getProperty("mindconnect.task-queue.retention", Duration.class));
        Optional.ofNullable(env.getProperty("mindconnect.task-queue.maintenance-interval", Duration.class)).ifPresent(queue::maintenanceInterval);
        if ("jdbc".equalsIgnoreCase(env.getProperty("mindconnect.task-queue.store", "memory"))) queue.jdbc();
        Optional.ofNullable(blankToNull(env.getProperty("mindconnect.task-queue.node-id"))).ifPresent(queue::nodeId);
        Optional.ofNullable(env.getProperty("mindconnect.task-queue.lease", Duration.class)).ifPresent(queue::lease);
        builder.install(queue);
        builder.property("defaultBaseDir", env.getProperty("mindconnect.tools.base-dir", System.getProperty("user.home")))
                .property("tavilyApiKey", env.getProperty("mindconnect.tools.tavily-api-key", ""))
                .property("codeExecRuntime", env.getProperty("mindconnect.code-exec.runtime", "auto"))
                .property("codeExecNetwork", env.getProperty("mindconnect.code-exec.network", "none"))
                .property("codeExecLanguages", env.getProperty("mindconnect.code-exec.languages", ""))
                .property("codeExecMemory", env.getProperty("mindconnect.code-exec.memory", "512m"))
                .property("codeExecCpus", env.getProperty("mindconnect.code-exec.cpus", "1"))
                .property("codeExecTimeoutSeconds", env.getProperty("mindconnect.code-exec.timeout-seconds", "60"))
                .property("codeExecIdleSeconds", env.getProperty("mindconnect.code-exec.idle-seconds", "600"))
                // Unset: memory — or, under Postgres persistence, pgvector when the database has it.
                .property("vectorStoreBackend", env.getProperty("mindconnect.vector-store.backend", ""))
                .property("vectorStoreUrl", env.getProperty("mindconnect.vector-store.url", ""))
                .property("vectorStoreUser", env.getProperty("mindconnect.vector-store.user", ""))
                .property("vectorStorePassword", env.getProperty("mindconnect.vector-store.password", ""))
                .property("vectorStoreEmbeddingConfig", env.getProperty("mindconnect.vector-store.embedding-config", "embeddings"))
                .property("fileStoreBackend", env.getProperty("mindconnect.file-store.backend",
                        persistence instanceof Persistence.Postgres ? "postgres" : "filesystem"));

        // The server works in the namespace of the call: the scope its filter binds, the stores routed by it.
        ThreadBoundScope scope = boundScope.getIfAvailable();
        if (scope != null) builder.install(new NamespaceFeature().scope(scope));

        // The application's own features and last words.
        features.orderedStream().forEach(builder::install);
        customizers.orderedStream().forEach(c -> c.customize(builder));

        // Start-up work — schema, seeds, the first look at the stores — runs in the default namespace;
        // a strict scope would otherwise refuse the unbound main thread.
        AgentRuntime runtime = StartupScope.call(scope, new Namespace(namespace), builder::build);
        log.info("Agent runtime built: persistence {}, namespace {}, features {}",
                persistence.getClass().getSimpleName(), scope != null ? "per call" : namespace,
                runtime.features().all().stream().map(RuntimeFeature::name).toList());
        return runtime;
    }

    /** The tools bind their providers once the context is up — not while it is still refreshing. */
    @Bean
    ApplicationListener<ContextRefreshedEvent> toolWarmUp(AgentRuntime runtime, ObjectProvider<ThreadBoundScope> boundScope,
                                                          Environment env) {
        Namespace startup = new Namespace(env.getProperty("mindconnect.namespace", "local"));
        return event -> StartupScope.run(boundScope.getIfAvailable(), startup,
                () -> runtime.features().find(ToolsFeature.class).ifPresent(ToolsFeature::warmUp));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * A bean of the host for a type no feature registered — never one of the
     * exports below. Those hand the runtime's own beans to the application,
     * so resolving one while the runtime is still being built asks Spring for
     * the runtime again: a cycle that stops the application from starting. It
     * happened for {@code ToolRepository}, which the tools feature registers
     * on files only, as soon as a server ran on Postgres.
     */
    static <T> Optional<T> hostBean(ApplicationContext context, Class<T> type) {
        if (!(context.getAutowireCapableBeanFactory()
                instanceof ConfigurableListableBeanFactory factory)) {
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

    /** Whether {@code name} is one of this configuration's exports of the runtime's beans. */
    private static boolean exportedHere(
            ConfigurableListableBeanFactory factory, String name) {
        if (!factory.containsBeanDefinition(name)) return false;
        return factory.getBeanDefinition(name)
                instanceof AnnotatedBeanDefinition annotated
                && annotated.getFactoryMethodMetadata() != null
                && AgentRuntimeAutoConfiguration.class.getName()
                        .equals(annotated.getFactoryMethodMetadata().getDeclaringClassName());
    }

    // ── the runtime's beans, for the application's own beans to inject ─────

    /**
     * The scope the runtime works in, for the application's own beans to inject.
     * Only when nothing else declares one: with the namespace starter present its
     * {@link ThreadBoundScope} is both this bean and the runtime's.
     */
    @Bean
    @ConditionalOnMissingBean(ScopeSupplier.class)
    ScopeSupplier scopeSupplier(AgentRuntime r) { return r.beans().get(ScopeSupplier.class); }

    @Bean AgentChatService agentChatService(AgentRuntime r) { return r.beans().get(AgentChatService.class); }
    @Bean AgentSessionService agentSessionService(AgentRuntime r) { return r.beans().get(AgentSessionService.class); }
    @Bean AgentRegistryService agentRegistryService(AgentRuntime r) { return r.beans().get(AgentRegistryService.class); }
    @Bean AgentTaskRunner agentTaskRunner(AgentRuntime r) { return r.beans().get(AgentTaskRunner.class); }
    @Bean PromptRenderer promptRenderer(AgentRuntime r) { return r.beans().get(PromptRenderer.class); }
    @Bean WorkingDirBrowser workingDirBrowser(AgentRuntime r) { return r.beans().get(WorkingDirBrowser.class); }
    @Bean WorkingDirPolicy workingDirPolicy(AgentRuntime r) { return r.beans().get(WorkingDirPolicy.class); }
    @Bean UserHome userHome(AgentRuntime r) { return r.beans().get(UserHome.class); }
    @Bean InstructionFiles instructionFiles(AgentRuntime r) { return r.beans().get(InstructionFiles.class); }
    @Bean ToolApprovalRepository toolApprovalRepository(AgentRuntime r) { return r.beans().get(ToolApprovalRepository.class); }
    @Bean UserChannels userChannels(AgentRuntime r) { return r.beans().get(UserChannels.class); }
    @Bean SessionChannels sessionChannels(AgentRuntime r) { return r.beans().get(SessionChannels.class); }
    @Bean TodoListService todoListService(AgentRuntime r) { return r.beans().get(TodoListService.class); }
    @Bean DynamicToolActivations dynamicToolActivations(AgentRuntime r) { return r.beans().get(DynamicToolActivations.class); }
    @Bean SkillCatalog skillCatalog(AgentRuntime r) { return r.beans().get(SkillCatalog.class); }
    @Bean SkillRepository skillRepository(AgentRuntime r) { return r.beans().get(SkillRepository.class); }
    @Bean AgentDefinitionRepository agentDefinitionRepository(AgentRuntime r) { return r.beans().get(AgentDefinitionRepository.class); }
    @Bean AgentSessionRepository agentSessionRepository(AgentRuntime r) { return r.beans().get(AgentSessionRepository.class); }
    @Bean LlmCallTraceRepository llmCallTraceRepository(AgentRuntime r) { return r.beans().get(LlmCallTraceRepository.class); }
    @Bean WorkingMemoryRepository workingMemoryRepository(AgentRuntime r) { return r.beans().get(WorkingMemoryRepository.class); }
    @Bean ConversationSummaryRepository conversationSummaryRepository(AgentRuntime r) { return r.beans().get(ConversationSummaryRepository.class); }
    @Bean TodoListRepository todoListRepository(AgentRuntime r) { return r.beans().get(TodoListRepository.class); }
    @Bean ToolRegistry toolRegistry(AgentRuntime r) { return r.beans().get(ToolRegistry.class); }
    @Bean LlmConfigRepository llmConfigRepository(AgentRuntime r) { return r.beans().get(LlmConfigRepository.class); }
    @Bean LlmPriceRepository llmPriceRepository(AgentRuntime r) { return r.beans().get(LlmPriceRepository.class); }
    @Bean LlmEmbeddings llmEmbeddings(AgentRuntime r) { return r.beans().get(LlmEmbeddings.class); }
    @Bean LlmTranscription llmTranscription(AgentRuntime r) { return r.beans().get(LlmTranscription.class); }
    /** The routing chat by its class too: the config test service asks for the concrete type. */
    @Bean RoutingLlmChatService llmChat(AgentRuntime r) { return (RoutingLlmChatService) r.beans().get(LlmChat.class); }
    @Bean ConversationManager conversationManager(AgentRuntime r) { return r.beans().get(ConversationManager.class); }
    @Bean MessageRepository messageRepository(AgentRuntime r) { return r.beans().get(MessageRepository.class); }
    /** The queue by its class too: the task monitor reads the local queue's records. */
    @Bean LocalTaskQueue taskQueue(AgentRuntime r) { return (LocalTaskQueue) r.beans().get(TaskQueue.class); }
    @Bean FileStore fileStore(AgentRuntime r) { return r.beans().get(FileStore.class); }
    @Bean VectorStores vectorStores(AgentRuntime r) { return r.beans().get(VectorStores.class); }
    @Bean WorkflowDataRepository workflowDataRepository(AgentRuntime r) { return r.beans().get(WorkflowDataRepository.class); }
    @Bean WorkflowInstanceRepository workflowInstanceRepository(AgentRuntime r) { return r.beans().get(WorkflowInstanceRepository.class); }

    /** The operator's tool settings — on files; a Postgres installation has none yet. */
    @Bean
    @ConditionalOnMissingBean(ToolRepository.class)
    ToolRepository toolRepository(AgentRuntime r) {
        return r.beans().find(ToolRepository.class).orElse(null);
    }
}
