package ai.mindconnect.agent.runtime.feature.core;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.runtime.adapter.file.FileAgentRepositoryFactory;
import ai.mindconnect.agent.runtime.adapter.pg.PgAgentRepositoryFactory;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentRepositoryFactory;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.kernel.ConfigurableFeature;
import ai.mindconnect.kernel.FeatureContext;
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
import ai.mindconnect.llm.adapter.env.EncryptingLlmConfigRepository;
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
import ai.mindconnect.llm.port.out.LlmPriceRepository;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
     * The one place the persistence setting is looked at: a repository factory
     * per domain module, each a lazy bean built once, on first use, with the
     * runtime's scope. The repository beans below ask it, and so does another
     * feature that needs a repository of these modules
     * ({@code ctx.require(AgentRepositoryFactory.class)}).
     */
    private void installRepositoryFactories(FeatureContext ctx) {
        ctx.bean(LlmRepositoryFactory.class, () -> switch (ctx.require(Persistence.class)) {
            case Persistence.InMemory m -> new InMemoryLlmRepositoryFactory(scope(ctx));
            case Persistence.File f -> new FileLlmRepositoryFactory(f.dataDir(), ctx.require(ObjectMapper.class), scope(ctx));
            case Persistence.Postgres p -> new PgLlmRepositoryFactory(sql(ctx), scope(ctx));
        });
        ctx.bean(MessageRepositoryFactory.class, () -> switch (ctx.require(Persistence.class)) {
            case Persistence.InMemory m -> new InMemoryMessageRepositoryFactory(scope(ctx));
            case Persistence.File f -> new FileMessageRepositoryFactory(f.dataDir(), ctx.require(ObjectMapper.class), scope(ctx));
            case Persistence.Postgres p -> new PgMessageRepositoryFactory(sql(ctx), scope(ctx));
        });
        ctx.bean(AgentRepositoryFactory.class, () -> switch (ctx.require(Persistence.class)) {
            case Persistence.InMemory m -> new InMemoryAgentRepositoryFactory(scope(ctx), maxTracesPerConversation);
            case Persistence.File f -> new FileAgentRepositoryFactory(f.dataDir(), ctx.require(ObjectMapper.class), scope(ctx),
                    maxTracesPerConversation);
            case Persistence.Postgres p -> new PgAgentRepositoryFactory(sql(ctx), scope(ctx), maxTracesPerConversation);
        });
    }

    private static ScopeSupplier scope(FeatureContext ctx) {
        return ctx.require(ScopeSupplier.class);
    }

    private void installLlm(FeatureContext ctx) {
        EncryptionHelper encryption = new EncryptionHelper(encryptionKey);
        ctx.instance(EncryptionHelper.class, encryption);
        ctx.bean(LlmConfigRepository.class, () -> ctx.require(LlmRepositoryFactory.class).llmConfigRepository());
        // What the configs cost, per period — its own entity beside the configs, built the same way.
        ctx.bean(LlmPriceRepository.class, () -> ctx.require(LlmRepositoryFactory.class).llmPriceRepository());
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
            var mapper = ctx.require(ObjectMapper.class);
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
                ctx.require(OkHttpClient.class), ctx.require(ObjectMapper.class), encryption, ctx.require(EnvVarResolver.class)));
        ctx.onStart(() -> {
            var repository = ctx.require(LlmConfigRepository.class);
            llmConfigs.forEach(repository::save);
        });
    }

    private void installMessages(FeatureContext ctx) {
        ctx.bean(ConversationRepository.class, () -> ctx.require(MessageRepositoryFactory.class).conversationRepository());
        ctx.bean(MessageRepository.class, () -> ctx.require(MessageRepositoryFactory.class).messageRepository());
        ctx.bean(ConversationManager.class, () -> new ConversationService(
                ctx.require(ConversationRepository.class), ctx.require(MessageRepository.class)));
    }

    private void installAgents(FeatureContext ctx) {
        if (usersHome != null) ctx.property("usersHome", usersHome);
        if (workingDirRoot != null) ctx.property("workingDirRoot", workingDirRoot.toString());
        if (workingDirChoice != null) ctx.property("workingDirChoice", workingDirChoice.toString());
        if (instructionsUserDir != null) ctx.property("instructionsUserDir", instructionsUserDir);

        Persistence persistence = ctx.require(Persistence.class);
        ctx.bean(AgentDefinitionRepository.class, () -> ctx.require(AgentRepositoryFactory.class).agentDefinitionRepository());
        ctx.bean(AgentSessionRepository.class, () -> ctx.require(AgentRepositoryFactory.class).agentSessionRepository());
        ctx.bean(WorkingMemoryRepository.class, () -> ctx.require(AgentRepositoryFactory.class).workingMemoryRepository());
        ctx.bean(ConversationSummaryRepository.class, () -> ctx.require(AgentRepositoryFactory.class).conversationSummaryRepository());
        ctx.bean(TodoListRepository.class, () -> ctx.require(AgentRepositoryFactory.class).todoListRepository());
        ctx.bean(LlmCallTraceRepository.class, () -> ctx.require(AgentRepositoryFactory.class).llmCallTraceRepository());
        ctx.bean(AgentRegistryService.class, () -> new AgentRegistryService(ctx.require(AgentDefinitionRepository.class)));
        ctx.bean(TodoListService.class, () -> new TodoListService(ctx.require(TodoListRepository.class)));
        // The todo list in the prompt, and the nudge to continue it after a tool call. Contributions are
        // instances, so these resolve the service on first use rather than now.
        ctx.contribute(PromptContextProvider.class, new LazyTodoPromptContext(ctx));
        ctx.contribute(ToolAdvisor.class, new LazyTodoContinuation(ctx));

        // What agents remember about each user across chats: the memory tools write it, and an agent
        // that has them sees the index in its system prompt.
        ctx.bean(UserMemoryRepository.class, () -> ctx.require(AgentRepositoryFactory.class).userMemoryRepository());
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
