package ai.mindconnect.agent.runtime.feature.core;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.runtime.adapter.file.FileAgentRepositoryFactory;
import ai.mindconnect.agent.runtime.adapter.pg.PgAgentRepositoryFactory;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentRepositoryFactory;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.feature.ConfigurableFeature;
import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.NamespaceRouting;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentRepositoryFactory;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.runtime.service.AgentRegistryService;
import ai.mindconnect.agent.runtime.service.UserHome;
import ai.mindconnect.agent.runtime.service.WorkingDirBrowser;
import ai.mindconnect.agent.runtime.service.WorkingDirPolicy;
import ai.mindconnect.agent.runtime.service.prompt.InstructionFiles;
import ai.mindconnect.agent.runtime.service.prompt.PromptSection;
import ai.mindconnect.agent.runtime.usermemory.MemoryIndex;
import ai.mindconnect.agent.runtime.usermemory.UserMemoryRepository;
import ai.mindconnect.agent.runtime.usermemory.UserMemoryService;
import ai.mindconnect.agent.runtime.tools.todo.TodoListRepository;
import ai.mindconnect.agent.runtime.tools.todo.TodoContinuationAdvisor;
import ai.mindconnect.agent.runtime.tools.todo.TodoListPromptContextProvider;
import ai.mindconnect.agent.runtime.tools.todo.TodoListService;
import ai.mindconnect.agent.runtime.port.out.PromptContextProvider;
import ai.mindconnect.agent.tool.ToolAdvisor;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.anthropic.ClaudeGateway;
import ai.mindconnect.llm.adapter.file.EncryptingLlmConfigRepository;
import ai.mindconnect.llm.adapter.file.FileLlmRepositoryFactory;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmRepositoryFactory;
import ai.mindconnect.llm.adapter.pg.PgLlmRepositoryFactory;
import ai.mindconnect.llm.adapter.gemini.GeminiGateway;
import ai.mindconnect.llm.adapter.openai.AzureOpenAiGateway;
import ai.mindconnect.llm.adapter.openai.OpenAiCompatibleGateway;
import ai.mindconnect.llm.adapter.openai.OpenAiResponsesGateway;
import ai.mindconnect.llm.adapter.openai.OpenAiEmbeddingsGateway;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmGateway;
import ai.mindconnect.llm.port.out.LlmGatewayRegistry;
import ai.mindconnect.llm.port.out.LlmRepositoryFactory;
import ai.mindconnect.llm.service.DefaultLlmGatewayRegistry;
import ai.mindconnect.llm.service.RoutingLlmChatService;
import ai.mindconnect.message.adapter.file.FileMessageRepositoryFactory;
import ai.mindconnect.message.adapter.memory.InMemoryMessageRepositoryFactory;
import ai.mindconnect.message.adapter.pg.PgMessageRepositoryFactory;
import ai.mindconnect.message.port.in.ConversationManager;
import ai.mindconnect.message.port.out.ConversationRepository;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.message.port.out.MessageRepositoryFactory;
import ai.mindconnect.message.service.ConversationService;
import okhttp3.OkHttpClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * What every agent runtime has, and the smallest one that chats: the
 * <b>LLM layer</b> (the config repository, encrypted when a key is given, one
 * gateway per provider family behind a routing chat, embeddings),
 * <b>conversations and messages</b>, and <b>agents</b> — definitions and
 * everything a session of one keeps: the session, working memory, summaries,
 * todos, LLM call traces, and where it may work on disk.
 *
 * <p>Every builder installs it. It is reached to configure —
 * {@code builder.feature(CoreFeature.class)} — or replaced by installing an
 * instance of this class or a subclass. A runtime without a model, a
 * transcript or a session is not an agent runtime, which is why the three are
 * one feature and not three optional ones.
 *
 * <p>The directory settings are published as properties ({@code usersHome},
 * {@code workingDirRoot}, {@code workingDirChoice}, {@code instructionsUserDir})
 * so that tools see the same values through their environment.
 */
public class CoreFeature extends ConfigurableFeature {

    private String encryptionKey;
    private String defaultLlmConfigName;
    private final List<LlmConfig> llmConfigs = new ArrayList<>();
    private final List<AgentDefinition> definitions = new ArrayList<>();
    private String usersHome;
    private Path workingDirRoot;
    private Boolean workingDirChoice;
    private String instructionsUserDir;
    private int maxTracesPerConversation = 50;
    // The factories per namespace, set while installing: what the routing builds a namespace's adapters from.
    private Function<Namespace, LlmRepositoryFactory> llmRepositories;
    private Function<Namespace, MessageRepositoryFactory> messageRepositories;
    private Function<Namespace, AgentRepositoryFactory> agentRepositories;

    // ── LLM ────────────────────────────────────────────────────────────────

    /** Key for the api keys at rest (16/24/32 chars). Without one they are stored plain. */
    public CoreFeature encryptionKey(String key) {
        changing();
        this.encryptionKey = key;
        return this;
    }

    /** Config for the runtime's internal stateless tasks; defaults to the single seeded config. */
    public CoreFeature defaultLlmConfigName(String name) {
        changing();
        this.defaultLlmConfigName = name;
        return this;
    }

    /** Saved into the repository on start. */
    public CoreFeature llmConfig(LlmConfig config) {
        changing();
        llmConfigs.add(config);
        return this;
    }

    public String defaultLlmConfigName() {
        if (defaultLlmConfigName != null) return defaultLlmConfigName;
        return llmConfigs.size() == 1 ? llmConfigs.get(0).name() : null;
    }

    // ── agents ─────────────────────────────────────────────────────────────

    /** Saved into the repository on start. */
    public CoreFeature agentDefinition(AgentDefinition definition) {
        changing();
        definitions.add(definition);
        return this;
    }

    /** Template of a user's home directory, {@code {user}} substituted; default {@code <dataDir>/<namespace>/users/{user}}. */
    public CoreFeature usersHome(String template) {
        changing();
        this.usersHome = template;
        return this;
    }

    /** The root every session working directory must lie under; default the user's home. */
    public CoreFeature workingDirRoot(Path root) {
        changing();
        this.workingDirRoot = root;
        return this;
    }

    /** Whether a session may pick its own working directory (default true). */
    public CoreFeature workingDirChoice(boolean allowed) {
        changing();
        this.workingDirChoice = allowed;
        return this;
    }

    /** Where a user's standing instruction files live; see {@link InstructionFiles}. */
    public CoreFeature instructionsUserDir(String template) {
        changing();
        this.instructionsUserDir = template;
        return this;
    }

    /** The LLM call traces kept per conversation, oldest dropped (default 50); 0 keeps all. */
    public CoreFeature maxTracesPerConversation(int max) {
        changing();
        this.maxTracesPerConversation = max;
        return this;
    }

    /** The agent repositories of one namespace — what other features route their own beans from. */
    public AgentRepositoryFactory agentRepositories(Namespace namespace) {
        return agentRepositories.apply(namespace);
    }

    public MessageRepositoryFactory messageRepositories(Namespace namespace) {
        return messageRepositories.apply(namespace);
    }

    public LlmRepositoryFactory llmRepositories(Namespace namespace) {
        return llmRepositories.apply(namespace);
    }

    @Override
    public String name() {
        return "core";
    }

    @Override
    protected void install(FeatureContext ctx) {
        installRepositoryFactories(ctx);
        installLlm(ctx);
        installMessages(ctx);
        installAgents(ctx);
    }

    /**
     * The one place the persistence setting is looked at: it picks a repository
     * factory per domain module — as a function of the namespace, so that the
     * runtime's {@link NamespaceRouting} can build a namespace's adapters on demand.
     * Every repository bean below is routed through it.
     */
    private void installRepositoryFactories(FeatureContext ctx) {
        switch (ctx.persistence()) {
            case Persistence.InMemory m -> {
                llmRepositories = ns -> new InMemoryLlmRepositoryFactory();
                messageRepositories = ns -> new InMemoryMessageRepositoryFactory();
                agentRepositories = ns -> new InMemoryAgentRepositoryFactory(maxTracesPerConversation);
            }
            case Persistence.File f -> {
                llmRepositories = ns -> new FileLlmRepositoryFactory(f.dataDir(), ns);
                messageRepositories = ns -> new FileMessageRepositoryFactory(f.dataDir(), ctx.objectMapper(), ns);
                agentRepositories = ns -> new FileAgentRepositoryFactory(f.dataDir(), ctx.objectMapper(), ns, maxTracesPerConversation);
            }
            case Persistence.Postgres p -> {
                llmRepositories = ns -> new PgLlmRepositoryFactory(sql(ctx), ns);
                messageRepositories = ns -> new PgMessageRepositoryFactory(sql(ctx), ns);
                agentRepositories = ns -> new PgAgentRepositoryFactory(sql(ctx), ns, maxTracesPerConversation);
            }
        }
    }

    private static NamespaceRouting routing(FeatureContext ctx) {
        return ctx.require(NamespaceRouting.class);
    }

    private void installLlm(FeatureContext ctx) {
        EncryptionHelper encryption = new EncryptionHelper(encryptionKey);
        ctx.instance(EncryptionHelper.class, encryption);
        ctx.bean(LlmConfigRepository.class, () -> routing(ctx).route(LlmConfigRepository.class,
                ns -> llmRepositories.apply(ns).llmConfigRepository()));
        if (encryptionKey != null) {
            // The decorator model: the store stays plain, the key wraps it.
            ctx.decorate(LlmConfigRepository.class, repo -> new EncryptingLlmConfigRepository(repo, encryption));
        }
        ctx.bean(OkHttpClient.class, () -> new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build());
        ctx.bean(LlmGatewayRegistry.class, () -> {
            var http = ctx.require(OkHttpClient.class);
            var mapper = ctx.objectMapper();
            // Placeholders in a config resolve through whatever the host gave the runtime:
            // the process environment, or a chain that asks the user and the namespace first.
            var env = ctx.require(EnvVarResolver.class);
            var openAi = new OpenAiCompatibleGateway(http, mapper, encryption, env);
            Map<LlmProvider, LlmGateway> gateways = new HashMap<>();
            for (LlmProvider provider : LlmProvider.values()) {
                gateways.put(provider, openAi);   // OpenAI-compatible is the safe default
            }
            // OpenAI itself speaks the Responses API: only there do tools and reasoning go together.
            gateways.put(LlmProvider.OPENAI, new OpenAiResponsesGateway(http, mapper, encryption, env));
            gateways.put(LlmProvider.ANTHROPIC, new ClaudeGateway(http, mapper, encryption, env));
            gateways.put(LlmProvider.AZURE_OPENAI, new AzureOpenAiGateway(http, mapper, encryption, env));
            gateways.put(LlmProvider.GOOGLE_GEMINI, new GeminiGateway(http, mapper, encryption, env));
            return new DefaultLlmGatewayRegistry(gateways);
        });
        ctx.bean(LlmChat.class, () -> new RoutingLlmChatService(
                ctx.require(LlmConfigRepository.class), ctx.require(LlmGatewayRegistry.class)));
        ctx.bean(LlmEmbeddings.class, () -> new OpenAiEmbeddingsGateway(
                ctx.require(OkHttpClient.class), ctx.objectMapper(), encryption, ctx.require(EnvVarResolver.class)));
        ctx.onStart(() -> {
            var repository = ctx.require(LlmConfigRepository.class);
            llmConfigs.forEach(repository::save);
        });
    }

    private void installMessages(FeatureContext ctx) {
        ctx.bean(ConversationRepository.class, () -> routing(ctx).route(ConversationRepository.class,
                ns -> messageRepositories.apply(ns).conversationRepository()));
        ctx.bean(MessageRepository.class, () -> routing(ctx).route(MessageRepository.class,
                ns -> messageRepositories.apply(ns).messageRepository()));
        ctx.bean(ConversationManager.class, () -> new ConversationService(
                ctx.require(ConversationRepository.class), ctx.require(MessageRepository.class)));
    }

    private void installAgents(FeatureContext ctx) {
        if (usersHome != null) ctx.property("usersHome", usersHome);
        if (workingDirRoot != null) ctx.property("workingDirRoot", workingDirRoot.toString());
        if (workingDirChoice != null) ctx.property("workingDirChoice", workingDirChoice.toString());
        if (instructionsUserDir != null) ctx.property("instructionsUserDir", instructionsUserDir);

        Persistence persistence = ctx.persistence();
        ctx.bean(AgentDefinitionRepository.class, () -> routing(ctx).route(AgentDefinitionRepository.class,
                ns -> agentRepositories.apply(ns).agentDefinitionRepository()));
        ctx.bean(AgentSessionRepository.class, () -> routing(ctx).route(AgentSessionRepository.class,
                ns -> agentRepositories.apply(ns).agentSessionRepository()));
        ctx.bean(WorkingMemoryRepository.class, () -> routing(ctx).route(WorkingMemoryRepository.class,
                ns -> agentRepositories.apply(ns).workingMemoryRepository()));
        ctx.bean(ConversationSummaryRepository.class, () -> routing(ctx).route(ConversationSummaryRepository.class,
                ns -> agentRepositories.apply(ns).conversationSummaryRepository()));
        ctx.bean(TodoListRepository.class, () -> routing(ctx).route(TodoListRepository.class,
                ns -> agentRepositories.apply(ns).todoListRepository()));
        ctx.bean(LlmCallTraceRepository.class, () -> routing(ctx).route(LlmCallTraceRepository.class,
                ns -> agentRepositories.apply(ns).llmCallTraceRepository()));
        ctx.bean(AgentRegistryService.class, () -> new AgentRegistryService(ctx.require(AgentDefinitionRepository.class)));
        ctx.bean(TodoListService.class, () -> new TodoListService(ctx.require(TodoListRepository.class)));
        // The todo list in the prompt, and the nudge to continue it after a tool call. Contributions are
        // instances, so these resolve the service on first use rather than now.
        ctx.contribute(PromptContextProvider.class, new LazyTodoPromptContext(ctx));
        ctx.contribute(ToolAdvisor.class, new LazyTodoContinuation(ctx));

        // What agents remember about each user across chats: the memory tools write it, and an agent
        // that has them sees the index in its system prompt.
        ctx.bean(UserMemoryRepository.class, () -> routing(ctx).route(UserMemoryRepository.class,
                ns -> agentRepositories.apply(ns).userMemoryRepository()));
        ctx.bean(UserMemoryService.class, () -> new UserMemoryService(ctx.require(UserMemoryRepository.class)));
        ctx.contribute(PromptSection.class, new LazyMemoryIndex(ctx));

        // Where a session may work: under workingDirRoot when set, else in the
        // user's own home — under the namespace the current call works in, so
        // every namespace has its own homes (a fixed scope always answers the same).
        ctx.bean(UserHome.class, () -> ctx.property("usersHome").filter(s -> !s.isBlank())
                .map(UserHome::of)
                .orElseGet(() -> {
                    var scope = ctx.require(ai.mindconnect.agent.ScopeSupplier.class);
                    return UserHome.underCurrent(() -> persistence.dataDir().resolve(scope.namespace().value()).toAbsolutePath());
                }));
        ctx.bean(WorkingDirPolicy.class, () -> {
            String root = ctx.property("workingDirRoot").orElse("");
            // The home template is asked per call, not now: under a thread-bound scope there is no namespace yet.
            WorkingDirPolicy policy = root.isBlank()
                    ? WorkingDirPolicy.withinCurrent(ctx.require(UserHome.class)::template)
                    : WorkingDirPolicy.within(root);
            return policy.withChoice(!"false".equalsIgnoreCase(ctx.property("workingDirChoice").orElse("true")));
        });
        ctx.bean(InstructionFiles.class, () -> InstructionFiles.of(ctx.property("instructionsUserDir").orElse("")));
        ctx.bean(WorkingDirBrowser.class, () -> new WorkingDirBrowser(ctx.require(WorkingDirPolicy.class)));

        ctx.onStart(() -> {
            var repository = ctx.require(AgentDefinitionRepository.class);
            definitions.forEach(repository::save);
        });
    }

    private static ai.mindconnect.jdbc.Sql sql(FeatureContext ctx) {
        return ctx.require(ai.mindconnect.jdbc.Sql.class);
    }

    private static Namespace namespace(FeatureContext ctx) {
        return ctx.require(Namespace.class);
    }
    /** The todo prompt context, its service resolved on first use — contributions are instances, beans are lazy. */
    private static class LazyTodoPromptContext implements PromptContextProvider {
        private final FeatureContext ctx;
        private volatile PromptContextProvider delegate;
        LazyTodoPromptContext(FeatureContext ctx) { this.ctx = ctx; }
        private PromptContextProvider delegate() {
            if (delegate == null) delegate = new TodoListPromptContextProvider(ctx.require(TodoListService.class));
            return delegate;
        }
        @Override public int priority() { return delegate().priority(); }
        @Override public void contribute(java.util.Map<String, Object> promptContext, AgentDefinition def,
                                         ai.mindconnect.agent.runtime.domain.AgentSession session,
                                         ai.mindconnect.agent.AuthenticationInfo auth) {
            delegate().contribute(promptContext, def, session, auth);
        }
    }

    /** The memory index, its service resolved on first use — like the todo prompt context. */
    private static class LazyMemoryIndex implements PromptSection {
        private final FeatureContext ctx;
        private volatile PromptSection delegate;
        LazyMemoryIndex(FeatureContext ctx) { this.ctx = ctx; }
        @Override public String render(AgentDefinition def, ai.mindconnect.agent.runtime.domain.AgentSession session) {
            if (delegate == null) delegate = new MemoryIndex(ctx.require(UserMemoryService.class));
            return delegate.render(def, session);
        }
    }

    private static class LazyTodoContinuation implements ToolAdvisor {
        private final FeatureContext ctx;
        private volatile ToolAdvisor delegate;
        LazyTodoContinuation(FeatureContext ctx) { this.ctx = ctx; }
        private ToolAdvisor delegate() {
            if (delegate == null) delegate = new TodoContinuationAdvisor(ctx.require(TodoListService.class));
            return delegate;
        }
        @Override public Result around(Invocation inv, Chain chain) throws Exception { return delegate().around(inv, chain); }
        @Override public int order() { return delegate().order(); }
        @Override public boolean applies(Invocation inv) { return delegate().applies(inv); }
    }
}
