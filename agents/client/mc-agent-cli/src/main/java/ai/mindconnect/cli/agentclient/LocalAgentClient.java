package ai.mindconnect.cli.agentclient;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.agent.port.in.ChatTurnHandle;
import ai.mindconnect.agent.service.AgentChatService;
import ai.mindconnect.agent.service.AgentRegistryService;
import ai.mindconnect.agent.service.AgentSessionService;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.message.domain.Message;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * Local (in-process) implementation of {@link AgentClient}.
 *
 * <p>Delegates directly to the runtime's use-case services
 * ({@link AgentRegistryService}, {@link AgentSessionService},
 * {@link AgentChatService}). No adapter façade is interposed because the CLI
 * already runs inside the same JVM as the runtime and provides its own
 * namespace/user context per call.
 */
public class LocalAgentClient implements AgentClient {

    private final AgentRegistryService registryService;
    private final AgentSessionService sessionService;
    private final AgentChatService chatService;

    public LocalAgentClient(AgentRegistryService registryService,
                             AgentSessionService sessionService,
                             AgentChatService chatService) {
        this.registryService = registryService;
        this.sessionService = sessionService;
        this.chatService = chatService;
    }

    // ── Agents ──────────────────────────────────────────────────────────────

    @Override
    public Optional<AgentDefinition> findAgent(Namespace namespace, UUID agentId) {
        return registryService.find(namespace, agentId);
    }

    @Override
    public List<AgentDefinition> listAgents(Namespace namespace) {
        return registryService.list(namespace);
    }

    // ── Sessions ────────────────────────────────────────────────────────────

    @Override
    public AgentSession startSession(UUID agentDefinitionId, Namespace namespace, String userId) {
        return sessionService.openChat(agentDefinitionId, namespace, userId);
    }

    @Override
    public List<AgentSession> listSessions(UUID agentDefinitionId, Namespace namespace, String userId) {
        return sessionService.listSessions(agentDefinitionId, namespace, userId);
    }

    @Override
    public List<Message> loadHistory(UUID sessionId) {
        return sessionService.loadHistory(sessionId);
    }

    @Override
    public void deleteSession(UUID sessionId) {
        sessionService.deleteSession(sessionId);
    }

    @Override
    public int deleteMessages(UUID sessionId, int fromSeq, int toSeq) {
        return sessionService.deleteMessages(sessionId, fromSeq, toSeq);
    }

    // ── Chat ────────────────────────────────────────────────────────────────

    @Override
    public String chat(UUID sessionId, String userMessage, Consumer<StreamEvent> eventHandler) {
        ChatTurnHandle handle = chatService.submitChat(sessionId, userMessage, eventHandler);
        try {
            return handle.result().join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CancellationException ce) throw ce;
            if (cause instanceof RuntimeException re) throw re;
            if (cause != null) throw new RuntimeException(cause);
            throw e;
        }
    }

    @Override
    public boolean cancelChat(UUID sessionId) {
        return chatService.cancelChat(sessionId);
    }

    // ── Memory ──────────────────────────────────────────────────────────────

    @Override
    public WorkingMemory getWorkingMemory(UUID sessionId) {
        return chatService.memorySnapshot(sessionId);
    }

    @Override
    public int compressMemory(UUID sessionId) {
        return chatService.compressMemory(sessionId);
    }
}
