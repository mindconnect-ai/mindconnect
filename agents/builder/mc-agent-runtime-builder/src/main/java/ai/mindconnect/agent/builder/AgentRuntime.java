package ai.mindconnect.agent.builder;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.agent.port.in.ChatTurnHandle;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.service.AgentChatService;
import ai.mindconnect.agent.service.AgentSessionService;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.message.port.in.ConversationManager;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * The embedded runtime handle built by {@link AgentRuntimeBuilder}: open
 * sessions, chat (blocking or streaming), attach files, close. A thin facade —
 * the underlying services are exposed for anything the facade doesn't cover.
 */
public final class AgentRuntime implements AutoCloseable {

    private final AgentChatService chatService;
    private final AgentSessionService sessionService;
    private final AgentDefinitionRepository definitionRepository;
    private final LlmConfigRepository llmConfigRepository;
    private final ConversationManager conversationManager;
    private final Namespace namespace;
    private final ExecutorService turnExecutor;
    private final AttachSupport attachSupport;   // null when the file/vector modules are absent
    private final ai.mindconnect.agent.service.approval.ToolApprovalStore approvalStore;

    AgentRuntime(AgentChatService chatService, AgentSessionService sessionService,
                 AgentDefinitionRepository definitionRepository, LlmConfigRepository llmConfigRepository,
                 ConversationManager conversationManager,
                 Namespace namespace, ExecutorService turnExecutor, AttachSupport attachSupport,
                 ai.mindconnect.agent.service.approval.ToolApprovalStore approvalStore) {
        this.chatService = chatService;
        this.sessionService = sessionService;
        this.definitionRepository = definitionRepository;
        this.llmConfigRepository = llmConfigRepository;
        this.conversationManager = conversationManager;
        this.namespace = namespace;
        this.turnExecutor = turnExecutor;
        this.attachSupport = attachSupport;
        this.approvalStore = approvalStore;
    }

    /** Opens a chat session with the named agent. */
    public AgentSession openSession(String agentName, String userId) {
        AgentDefinition def = definitionRepository.findByName(namespace, agentName)
                .orElseThrow(() -> new IllegalArgumentException("No agent named '" + agentName
                        + "' in namespace '" + namespace.value() + "'"));
        return sessionService.openChat(def.id(), namespace, userId);
    }

    /** Sends one message in an existing session and blocks for the answer. */
    public String chat(UUID sessionId, String message, Consumer<StreamEvent> events) {
        ChatTurnHandle handle = chatService.submitChat(sessionId, message, events);
        try {
            return handle.result().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the agent", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Chat turn failed: " + e.getCause().getMessage(), e.getCause());
        }
    }

    /** One-shot convenience: open a session, ask, return the answer. */
    public String ask(String agentName, String userId, String message, Consumer<StreamEvent> events) {
        AgentSession session = openSession(agentName, userId);
        return chat(session.id(), message, events);
    }

    /**
     * Attaches a file to a chat session, exactly like the server's chat
     * upload: stored in the file store, chunked + embedded through the
     * ingestion workflow into the session's vector store, and announced to
     * the agent (vector_search activation + system-prompt section).
     *
     * <p>Requires the optional modules {@code mc-file-store},
     * {@code mc-vector-store-tools} and {@code mc-workflow-admin-rest} on the
     * classpath, and an ingestion workflow (see
     * {@link AgentRuntimeBuilder#workflowFromClasspath}).
     */
    public String attachFile(UUID sessionId, String fileName, java.io.InputStream content) {
        requireAttachSupport();
        return attachSupport.attach(sessionId, fileName, content);
    }

    private void requireAttachSupport() {
        if (attachSupport == null) {
            throw new IllegalStateException("file support needs the optional modules mc-file-store "
                    + "and mc-vector-store-tools on the classpath");
        }
    }

    // ── escape hatches ─────────────────────────────────────────────────────

    public AgentChatService chatService() { return chatService; }
    /** The open-approval registry — cards render from it, tests assert on it. */
    public ai.mindconnect.agent.service.approval.ToolApprovalStore approvalStore() { return approvalStore; }
    public AgentSessionService sessionService() { return sessionService; }
    public AgentDefinitionRepository agentDefinitions() { return definitionRepository; }
    public LlmConfigRepository llmConfigs() { return llmConfigRepository; }
    public ConversationManager conversationManager() { return conversationManager; }

    /**
     * The file store behind attach support — upload here, then reference the
     * id from message content or ingest via {@link #attachStored}. Throws
     * when the optional modules are absent (same rule as {@link #attachFile}).
     */
    public ai.mindconnect.filestore.FileStore fileStore() {
        requireAttachSupport();
        return attachSupport.fileStore();
    }

    /** Ingests an already-uploaded file into the session's vector store. */
    public String attachStored(UUID sessionId, ai.mindconnect.filestore.StoredFile stored) {
        requireAttachSupport();
        return attachSupport.attachStored(sessionId, stored);
    }
    public Namespace namespace() { return namespace; }

    @Override
    public void close() {
        turnExecutor.shutdown();
    }
}
