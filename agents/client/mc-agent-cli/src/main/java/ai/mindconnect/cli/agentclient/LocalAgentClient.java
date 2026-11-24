package ai.mindconnect.cli.agentclient;

import ai.mindconnect.cli.agentclient.StreamEvent;
import ai.mindconnect.agent.loop.domain.TurnEvent;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.TurnOutcome;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionAgent;
import ai.mindconnect.agent.management.api.SessionDirectories;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.InterruptionAnswer;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.port.in.TurnListener;
import ai.mindconnect.agent.runtime.capabilities.memory.MemoryCompaction;
import ai.mindconnect.agent.runtime.capabilities.memory.MemoryInspection;
import ai.mindconnect.agent.runtime.capabilities.memory.WorkingMemory;
import ai.mindconnect.agent.runtime.engine.adapter.store.RepositorySessionStore;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.port.out.MessageRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The runtime in this process: every call asks the {@link AgentRuntime} the
 * application built.
 */
public class LocalAgentClient implements AgentClient {

    private static final PageRequest EVERYTHING = new PageRequest(0, Integer.MAX_VALUE);

    private final AgentRuntime runtime;

    public LocalAgentClient(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    private AgentManagement agents() {
        return runtime.service(AgentManagement.class);
    }

    private SessionManagement sessions() {
        return runtime.service(SessionManagement.class);
    }

    // ── Agents ──────────────────────────────────────────────────────────────

    @Override
    public Optional<ManagedAgent> findAgent(AgentId agentId) {
        return agents().find(agentId);
    }

    @Override
    public List<ManagedAgent> listAgents() {
        return agents().list();
    }

    // ── Sessions ────────────────────────────────────────────────────────────

    @Override
    public ManagedSession startSession(AgentId agentId, UserId userId) {
        return startSession(agentId, userId, null);
    }

    @Override
    public ManagedSession startSession(AgentId agentId, UserId userId, String workingDir) {
        return sessions().open(userId, null, new SessionAgent.Ref(agentId), null,
                new SessionDirectories(workingDir, List.of()));
    }

    @Override
    public ManagedSession changeWorkingDir(SessionId sessionId, String workingDir) {
        return changeWorkingDir(sessionId, workingDir, null);
    }

    @Override
    public ManagedSession changeWorkingDir(SessionId sessionId, String workingDir, List<String> additionalDirs) {
        ManagedSession current = sessions().find(sessionId).orElseThrow(() ->
                new IllegalArgumentException("There is no session " + sessionId.value()));
        return sessions().changeDirectories(sessionId, new SessionDirectories(
                workingDir == null || workingDir.isBlank() ? null : workingDir,
                additionalDirs == null ? current.directories().additional() : additionalDirs));
    }

    @Override
    public List<ManagedSession> listSessions(AgentId agentId, UserId userId) {
        return sessions().list(userId).stream()
                .filter(session -> session.agentId().map(agentId::equals).orElse(false))
                .toList();
    }

    @Override
    public List<Message> loadHistory(SessionId sessionId) {
        return runtime.service(MessageRepository.class).findByConversation(conversationOf(sessionId), EVERYTHING);
    }

    @Override
    public void deleteSession(SessionId sessionId) {
        sessions().delete(sessionId);
    }

    @Override
    public int deleteMessages(SessionId sessionId, int fromSeq, int toSeq) {
        if (fromSeq > toSeq) {
            throw new IllegalArgumentException("fromSeq must be <= toSeq");
        }
        MessageRepository messages = runtime.service(MessageRepository.class);
        ConversationId conversation = conversationOf(sessionId);
        int deleted = (int) messages.findByConversation(conversation, EVERYTHING).stream()
                .filter(message -> message.sequenceNum() >= fromSeq && message.sequenceNum() <= toSeq).count();
        messages.deleteBySequenceRange(conversation, fromSeq, toSeq);
        return deleted;
    }

    /** The conversation the session's messages are kept in. */
    private ConversationId conversationOf(SessionId sessionId) {
        return runtime.sessions().find(sessionId).map(RepositorySessionStore::conversationOf)
                .orElseThrow(() -> new IllegalArgumentException("There is no session " + sessionId.value()));
    }

    // ── Chat ────────────────────────────────────────────────────────────────

    @Override
    public String chat(SessionId sessionId, String userMessage, Consumer<StreamEvent> eventHandler) {
        // A stored agent the session refers to holds as edited since.
        AgentDefinition agent = sessions().agentOf(sessionId).orElseThrow(() ->
                new IllegalStateException("Session " + sessionId.value() + " has no agent"));
        runtime.sessions().changeAgent(sessionId, agent);

        CompletableFuture<Turn> over = new CompletableFuture<>();
        CompletableFuture<TurnId> started = new CompletableFuture<>();
        // Followed before the turn starts: that it asks is told by its session, and only once.
        var asking = runtime.sessions().lifecycle(sessionId, true, event -> {
            if (event instanceof SessionEvent.TurnInterrupted interrupted && !over.isDone()) {
                for (Interruption open : interrupted.interruptions()) {
                    if (Interruption.TOOL_APPROVAL.equals(open.kind()) && open.call() != null) {
                        eventHandler.accept(new StreamEvent.ApprovalRequested(open.id(), open.call().callId(),
                                open.call().toolName(), open.call().arguments(),
                                open.call().sessionId(), null));
                    }
                }
                // The turn waits: whoever draws the question answers it through answerApproval.
            }
        });
        TurnId turnId = runtime.turns().submit(TurnRequest.text(sessionId, userMessage));
        started.complete(turnId);
        TurnStreamEvents translator = new TurnStreamEvents(turnId, runtime.turns(), eventHandler);
        var following = runtime.turns().subscribe(turnId, new TurnListener() {
            @Override
            public void onEvent(TurnEvent event) {
                if (!over.isDone()) {
                    translator.onEvent(event);
                }
            }

            @Override
            public void onFinished(Turn turn) {
                over.complete(turn);
            }
        });
        try {
            Turn turn = over.join();
            if (turn.status() == TurnStatus.CANCELLED) {
                throw new CancellationException("The turn was cancelled");
            }
            TurnOutcome outcome = turn.outcome();
            if (outcome == null) {
                throw new RuntimeException(turn.error() != null ? turn.error() : "The turn failed");
            }
            return outcome.text() == null ? "" : outcome.text();
        } finally {
            following.close();
            asking.close();
        }
    }

    @Override
    public boolean answersApprovals() {
        return true;
    }

    @Override
    public boolean answerApproval(SessionId sessionId, String callId, boolean approved, boolean forSession) {
        Optional<Turn> running = runtime.turns().runningTurn(sessionId);
        Optional<String> asked = running.map(Turn::interruptions).orElse(List.of()).stream()
                .filter(open -> open.call() != null && callId.equals(open.call().callId()))
                .map(Interruption::id)
                .findFirst();
        asked.ifPresent(id -> runtime.turns().resume(running.orElseThrow().id(), List.of(
                !approved ? InterruptionAnswer.reject(id, "")
                        : forSession ? InterruptionAnswer.approveAlways(id) : InterruptionAnswer.approve(id))));
        return asked.isPresent();
    }

    @Override
    public boolean cancelChat(SessionId sessionId) {
        Optional<Turn> running = runtime.turns().runningTurn(sessionId);
        running.ifPresent(turn -> runtime.turns().cancel(turn.id()));
        return running.isPresent();
    }

    // ── Memory ──────────────────────────────────────────────────────────────

    @Override
    public MemoryView getWorkingMemory(SessionId sessionId) {
        WorkingMemory memory = runtime.service(MemoryInspection.class).inspect(sessionId);
        List<MemoryView.Entry> sent = new ArrayList<>();
        for (int i = 0; i < memory.messages().size(); i++) {
            WorkingMemory.Entry entry = memory.messages().get(i);
            if (!entry.sent()) {
                continue;
            }
            TurnMessage.Type type = entry.message().type();
            boolean said = type == TurnMessage.Type.USER || type == TurnMessage.Type.ASSISTANT;
            sent.add(new MemoryView.Entry(said ? "CHAT" : type.name(), type == TurnMessage.Type.USER ? "USER" : "AGENT",
                    i + 1, entry.tokens(), entry.message().text(), entry.sentAs() != null, entry.sentAs()));
        }
        return new MemoryView(memory.instructions(), memory.instructionTokens(), sent, memory.totalTokens(),
                memory.contextWindowTokens().isPresent() ? memory.contextWindowTokens().getAsInt() : null);
    }

    @Override
    public int compressMemory(SessionId sessionId) {
        return runtime.service(MemoryCompaction.class).compact(sessionId);
    }
}
