package ai.mindconnect.cli.agentclient;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.StreamEvent;
import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.agent.runtime.port.in.ChatTurnHandle;
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.agent.runtime.service.AgentRegistryService;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.message.domain.Message;

import java.util.List;
import java.util.Optional;
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
 * user context per call.
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
    public Optional<AgentDefinition> findAgent(AgentId agentId) {
        return registryService.find(agentId);
    }

    @Override
    public List<AgentDefinition> listAgents() {
        return registryService.list();
    }

    // ── Sessions ────────────────────────────────────────────────────────────

    @Override
    public AgentSession startSession(AgentId agentDefinitionId, UserId userId) {
        return sessionService.openChat(agentDefinitionId, userId);
    }

    @Override
    public AgentSession startSession(AgentId agentDefinitionId, UserId userId, String workingDir) {
        return sessionService.openChat(agentDefinitionId, userId, workingDir);
    }

    @Override
    public AgentSession changeWorkingDir(SessionId sessionId, String workingDir) {
        return sessionService.changeWorkingDir(sessionId, workingDir);
    }

    @Override
    public AgentSession changeWorkingDir(SessionId sessionId, String workingDir, List<String> additionalDirs) {
        return sessionService.changeWorkingDir(sessionId, workingDir, additionalDirs);
    }

    @Override
    public List<AgentSession> listSessions(AgentId agentDefinitionId, UserId userId) {
        return sessionService.listSessions(agentDefinitionId, userId);
    }

    @Override
    public List<Message> loadHistory(SessionId sessionId) {
        return sessionService.loadHistory(sessionId);
    }

    @Override
    public void deleteSession(SessionId sessionId) {
        sessionService.deleteSession(sessionId);
    }

    @Override
    public int deleteMessages(SessionId sessionId, int fromSeq, int toSeq) {
        return sessionService.deleteMessages(sessionId, fromSeq, toSeq);
    }

    // ── Chat ────────────────────────────────────────────────────────────────

    @Override
    public String chat(SessionId sessionId, String userMessage, Consumer<StreamEvent> eventHandler) {
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
    public boolean cancelChat(SessionId sessionId) {
        return chatService.cancelChat(sessionId);
    }

    // ── Memory ──────────────────────────────────────────────────────────────

    @Override
    public WorkingMemory getWorkingMemory(SessionId sessionId) {
        return chatService.memorySnapshot(sessionId);
    }

    @Override
    public int compressMemory(SessionId sessionId) {
        return chatService.compressMemory(sessionId);
    }
}
