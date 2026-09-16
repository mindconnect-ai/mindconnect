package ai.mindconnect.agent.builder;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.StreamEvent;
import ai.mindconnect.agent.runtime.domain.session.InlineSessionAgent;
import ai.mindconnect.agent.runtime.feature.DefaultFeatureContext;
import ai.mindconnect.agent.runtime.feature.DefaultRuntimeBeans;
import ai.mindconnect.agent.runtime.feature.FeatureRegistry;
import ai.mindconnect.agent.runtime.feature.Features;
import ai.mindconnect.agent.runtime.feature.RuntimeBeans;
import ai.mindconnect.agent.runtime.feature.RuntimeView;
import ai.mindconnect.agent.runtime.feature.fileupload.AttachSupport;
import ai.mindconnect.agent.runtime.port.in.ChatTurnHandle;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.runtime.service.approval.ToolApprovalStore;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.message.port.in.ConversationManager;

import java.util.function.Consumer;

/**
 * A running agent runtime: the chat facade on top of the registries every
 * installed feature filled. Built by {@link AgentRuntimeBuilder}.
 *
 * <p>Beans are reached two ways — typed through the feature that owns them
 * ({@code runtime.feature(LlmFeature.class)}) or generically through
 * {@link #beans()} ({@code runtime.beans().get(LlmChat.class)}); the
 * convenience accessors below are the same lookups. The runtime is
 * {@link RuntimeView} so that a feature sees, while it is installed, exactly
 * what a caller sees afterwards.
 */
public class AgentRuntime implements RuntimeView, AutoCloseable {

    private final DefaultRuntimeBeans beans;
    private final FeatureRegistry features;
    private DefaultFeatureContext context;   // set by the builder once it exists — closing runs its hooks

    AgentRuntime(DefaultRuntimeBeans beans, FeatureRegistry features) {
        this.beans = beans;
        this.features = features;
    }

    void context(DefaultFeatureContext context) {
        this.context = context;
    }

    @Override
    public RuntimeBeans beans() {
        return beans;
    }

    @Override
    public Features features() {
        return features;
    }

    // ── sessions ───────────────────────────────────────────────────────────

    public AgentSession openSession(String agentName, UserId userId) {
        return openSession(agentName, userId, (java.nio.file.Path) null);
    }

    public AgentSession openSession(String agentName, UserId userId, java.nio.file.Path workingDir) {
        AgentDefinition def = agentDefinitions().findByName(agentName)
                .orElseThrow(() -> new IllegalArgumentException("No agent named '" + agentName + "'"));
        return sessionService().openChat(def.id(), userId, workingDir == null ? null : workingDir.toString());
    }

    public AgentSession changeWorkingDir(SessionId sessionId, java.nio.file.Path workingDir) {
        return sessionService().changeWorkingDir(sessionId, workingDir == null ? null : workingDir.toString());
    }

    public AgentSession addDirectory(SessionId sessionId, java.nio.file.Path dir) {
        return sessionService().addDirectory(sessionId, dir.toString());
    }

    /** A chat without a stored agent: a model, some tools, the default prompt. */
    public AgentSession openSession(String llmConfigName, java.util.List<String> toolNames, UserId userId) {
        return openSession(llmConfigName, toolNames, DEFAULT_CHAT_PROMPT, java.util.List.of(), userId);
    }

    public AgentSession openSession(String llmConfigName, java.util.List<String> toolNames,
                                    String systemPrompt, java.util.List<String> callableAgents,
                                    UserId userId) {
        if (llmConfigName == null || llmConfigName.isBlank()) {
            throw new IllegalArgumentException("A session without an agent needs a model name");
        }
        if (llmConfigs().findByName(llmConfigName).isEmpty()) {
            throw new IllegalArgumentException("No LLM config named '" + llmConfigName + "'");
        }
        var agent = InlineSessionAgent.of("Chat", systemPrompt, llmConfigName, toolNames, callableAgents);
        return sessionService().openChat(agent, userId);
    }

    public static final String DEFAULT_CHAT_PROMPT = """
            You are a helpful assistant. Be concise and practical.

            Today's date: {{ current_date }}
            """;

    // ── chat ───────────────────────────────────────────────────────────────

    public String ask(String llmConfigName, java.util.List<String> toolNames,
                      UserId userId, String message, Consumer<StreamEvent> events) {
        AgentSession session = openSession(llmConfigName, toolNames, userId);
        return chat(session.id(), message, events);
    }

    public String ask(String agentName, UserId userId, String message, Consumer<StreamEvent> events) {
        AgentSession session = openSession(agentName, userId);
        return chat(session.id(), message, events);
    }

    public String chat(SessionId sessionId, String message, Consumer<StreamEvent> events) {
        return chat(sessionId, ai.mindconnect.message.domain.ContentPart.text(message), events);
    }

    /** Sends one turn and blocks until the agent answered. */
    public String chat(SessionId sessionId, java.util.List<ai.mindconnect.message.domain.ContentPart> parts,
                       Consumer<StreamEvent> events) {
        ChatTurnHandle handle = chatService().submitChat(sessionId, parts, events);
        try {
            return handle.result().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the agent", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Chat turn failed: " + e.getCause().getMessage(), e.getCause());
        }
    }

    // ── files ──────────────────────────────────────────────────────────────

    /** Stores the file and attaches it to the session — needs the file-upload feature and its optional modules. */
    public String attachFile(SessionId sessionId, String fileName, java.io.InputStream content) {
        return attachSupport().attach(sessionId, fileName, content);
    }

    public String attachStored(SessionId sessionId, ai.mindconnect.filestore.StoredFile stored) {
        return attachSupport().attachStored(sessionId, stored);
    }

    public ai.mindconnect.filestore.FileStore fileStore() {
        return attachSupport().fileStore();
    }

    private AttachSupport attachSupport() {
        return beans.find(AttachSupport.class).orElseThrow(() -> new IllegalStateException(
                "file support needs the file-upload feature and the optional modules mc-file-store "
                        + "and mc-vector-store-tools on the classpath"));
    }

    // ── the same beans, by name ────────────────────────────────────────────

    public AgentChatService chatService() { return beans.get(AgentChatService.class); }
    public AgentSessionService sessionService() { return beans.get(AgentSessionService.class); }
    public ToolApprovalStore approvalStore() { return beans.get(ToolApprovalStore.class); }
    public AgentDefinitionRepository agentDefinitions() { return beans.get(AgentDefinitionRepository.class); }
    public LlmConfigRepository llmConfigs() { return beans.get(LlmConfigRepository.class); }
    public ConversationManager conversationManager() { return beans.get(ConversationManager.class); }

    /** Runs every feature's close hook, last installed first. */
    @Override
    public void close() {
        if (context != null) context.close();
    }
}
