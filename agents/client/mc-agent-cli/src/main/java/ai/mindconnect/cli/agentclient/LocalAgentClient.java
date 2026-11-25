package ai.mindconnect.cli.agentclient;

import ai.mindconnect.cli.agentclient.StreamEvent;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.ManagedMessage;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionAgent;
import ai.mindconnect.agent.management.api.SessionDirectories;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.InterruptionAnswer;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.capabilities.memory.MemoryCompaction;
import ai.mindconnect.agent.runtime.capabilities.memory.MemoryInspection;
import ai.mindconnect.agent.runtime.capabilities.memory.WorkingMemory;
import ai.mindconnect.agent.sdk.AgentRuntime;

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
    public List<ManagedMessage> loadHistory(SessionId sessionId) {
        return sessions().messages(sessionId);
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
        return sessions().deleteMessages(sessionId, fromSeq, toSeq);
    }

    // ── Chat ────────────────────────────────────────────────────────────────

    @Override
    public String chat(SessionId sessionId, String userMessage, Consumer<StreamEvent> eventHandler) {
        // A stored agent the session refers to holds as edited since.
        AgentDefinition agent = sessions().agentOf(sessionId).orElseThrow(() ->
                new IllegalStateException("Session " + sessionId.value() + " has no agent"));
        runtime.sessions().changeAgent(sessionId, agent);

        TurnId turnId = runtime.turns().submit(TurnRequest.text(sessionId, userMessage));
        CompletableFuture<String> answer = new CompletableFuture<>();
        TurnStreamEvents translator = new TurnStreamEvents(turnId);
        // The turn's own stream tells all of it: what it and its sub-agents do, that it asks, how it ends.
        // A turn that asks waits: whoever draws the question answers it through answerApproval.
        var following = runtime.turns().subscribe(turnId, event -> {
            if (answer.isDone()) {
                return;
            }
            translator.drawn(event).ifPresent(eventHandler);
            if (!turnId.equals(event.turnId())) {
                return;
            }
            event.as(ai.mindconnect.agent.runtime.api.domain.EventType.DONE).ifPresent(done -> {
                if (done.status() == TurnStatus.CANCELLED) {
                    answer.completeExceptionally(new CancellationException("The turn was cancelled"));
                } else {
                    answer.complete(done.text() == null ? "" : done.text());
                }
            });
            event.as(ai.mindconnect.agent.runtime.api.domain.EventType.ERROR).ifPresent(error ->
                    answer.completeExceptionally(error.status() == TurnStatus.CANCELLED
                            ? new CancellationException("The turn was cancelled")
                            : new RuntimeException(error.message())));
        });
        try {
            return answer.join();
        } catch (java.util.concurrent.CompletionException e) {
            throw e.getCause() instanceof RuntimeException failure ? failure : e;
        } finally {
            following.close();
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
