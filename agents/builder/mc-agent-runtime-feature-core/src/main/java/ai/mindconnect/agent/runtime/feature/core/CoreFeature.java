package ai.mindconnect.agent.runtime.feature.core;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.runtime.adapter.file.FileAgentRepositoryFactory;
import ai.mindconnect.agent.runtime.adapter.pg.PgAgentRepositoryFactory;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentRepositoryFactory;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.feature.ConfigurableFeature;
import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentRepositoryFactory;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.runtime.service.UserHome;
import ai.mindconnect.agent.runtime.service.WorkingDirPolicy;
import ai.mindconnect.agent.runtime.service.prompt.InstructionFiles;
import ai.mindconnect.agent.runtime.tools.todo.TodoListRepository;
import ai.mindconnect.agent.runtime.tools.todo.TodoListService;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.anthropic.ClaudeGateway;
import ai.mindconnect.llm.adapter.file.EncryptingLlmConfigRepository;
import ai.mindconnect.llm.adapter.file.FileLlmRepositoryFactory;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmRepositoryFactory;
import ai.mindconnect.llm.adapter.pg.PgLlmRepositoryFactory;
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
     * factory per domain module, and every repository bean below asks its factory.
     */
    private void installRepositoryFactories(FeatureContext ctx) {
        switch (ctx.persistence()) {
            case Persistence.InMemory m -> {
                ctx.instance(LlmRepositoryFactory.class, new InMemoryLlmRepositoryFactory());
                ctx.instance(MessageRepositoryFactory.class, new InMemoryMessageRepositoryFactory());
                ctx.instance(AgentRepositoryFactory.class, new InMemoryAgentRepositoryFactory());
            }
            case Persistence.File f -> {
                ctx.bean(LlmRepositoryFactory.class, () -> new FileLlmRepositoryFactory(f.dataDir(), namespace(ctx)));
                ctx.bean(MessageRepositoryFactory.class, () -> new FileMessageRepositoryFactory(f.dataDir(), ctx.objectMapper(), namespace(ctx)));
                ctx.bean(AgentRepositoryFactory.class, () -> new FileAgentRepositoryFactory(f.dataDir(), ctx.objectMapper(), namespace(ctx)));
            }
            case Persistence.Postgres p -> {
                ctx.bean(LlmRepositoryFactory.class, () -> new PgLlmRepositoryFactory(sql(ctx), namespace(ctx)));
                ctx.bean(MessageRepositoryFactory.class, () -> new PgMessageRepositoryFactory(sql(ctx), namespace(ctx)));
                ctx.bean(AgentRepositoryFactory.class, () -> new PgAgentRepositoryFactory(sql(ctx), namespace(ctx)));
            }
        }
    }

    private void installLlm(FeatureContext ctx) {
        EncryptionHelper encryption = new EncryptionHelper(encryptionKey);
        ctx.instance(EncryptionHelper.class, encryption);
        ctx.bean(LlmConfigRepository.class, () -> ctx.require(LlmRepositoryFactory.class).llmConfigRepository());
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
            var openAi = new OpenAiCompatibleGateway(http, mapper, encryption);
            Map<LlmProvider, LlmGateway> gateways = new HashMap<>();
            for (LlmProvider provider : LlmProvider.values()) {
                gateways.put(provider, openAi);   // OpenAI-compatible is the safe default
            }
            gateways.put(LlmProvider.ANTHROPIC, new ClaudeGateway(http, mapper, encryption));
            gateways.put(LlmProvider.AZURE_OPENAI, new AzureOpenAiGateway(http, mapper, encryption));
            gateways.put(LlmProvider.GOOGLE_GEMINI, new GeminiGateway(http, mapper, encryption));
            return new DefaultLlmGatewayRegistry(gateways);
        });
        ctx.bean(LlmChat.class, () -> new RoutingLlmChatService(
                ctx.require(LlmConfigRepository.class), ctx.require(LlmGatewayRegistry.class)));
        ctx.bean(LlmEmbeddings.class, () -> new OpenAiEmbeddingsGateway(
                ctx.require(OkHttpClient.class), ctx.objectMapper(), encryption));
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

        Persistence persistence = ctx.persistence();
        ctx.bean(AgentDefinitionRepository.class, () -> repositories(ctx).agentDefinitionRepository());
        ctx.bean(AgentSessionRepository.class, () -> repositories(ctx).agentSessionRepository());
        ctx.bean(WorkingMemoryRepository.class, () -> repositories(ctx).workingMemoryRepository());
        ctx.bean(ConversationSummaryRepository.class, () -> repositories(ctx).conversationSummaryRepository());
        ctx.bean(TodoListRepository.class, () -> repositories(ctx).todoListRepository());
        ctx.bean(LlmCallTraceRepository.class, () -> repositories(ctx).llmCallTraceRepository());
        ctx.bean(TodoListService.class, () -> new TodoListService(ctx.require(TodoListRepository.class)));

        // Where a session may work: under workingDirRoot when set, else in the
        // user's own home — the same rule the Spring apps apply.
        ctx.bean(UserHome.class, () -> ctx.property("usersHome").filter(s -> !s.isBlank())
                .map(UserHome::of)
                .orElseGet(() -> UserHome.under(persistence.dataDir().resolve(namespace(ctx).value()))));
        ctx.bean(WorkingDirPolicy.class, () -> {
            String root = ctx.property("workingDirRoot").orElse("");
            return WorkingDirPolicy.within(root.isBlank() ? ctx.require(UserHome.class).template() : root)
                    .withChoice(!"false".equalsIgnoreCase(ctx.property("workingDirChoice").orElse("true")));
        });
        ctx.bean(InstructionFiles.class, () -> InstructionFiles.of(ctx.property("instructionsUserDir").orElse("")));

        ctx.onStart(() -> {
            var repository = ctx.require(AgentDefinitionRepository.class);
            definitions.forEach(repository::save);
        });
    }

    private static AgentRepositoryFactory repositories(FeatureContext ctx) {
        return ctx.require(AgentRepositoryFactory.class);
    }

    private static ai.mindconnect.jdbc.Sql sql(FeatureContext ctx) {
        return ctx.require(ai.mindconnect.jdbc.Sql.class);
    }

    private static Namespace namespace(FeatureContext ctx) {
        return ctx.require(Namespace.class);
    }
}
