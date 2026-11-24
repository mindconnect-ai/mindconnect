package ai.mindconnect.agent.starter.workflow;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.chat.stream.ChatTurnHandle;
import ai.mindconnect.agent.chat.stream.ToolApproval;
import ai.mindconnect.agent.chat.stream.TurnResult;
import ai.mindconnect.agent.chat.stream.TurnStatus;
import ai.mindconnect.agent.loop.domain.TurnEvent;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;
import ai.mindconnect.agent.runtime.api.port.in.TurnListener;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.message.domain.ChatTurnId;

/**
 * A turn of the runtime as an agent-call step waits for it: its first
 * outcome is the answer, the questions it stopped for, or its failure. The
 * step is told about a stop as well as about an end, because nobody answers
 * an approval inside a workflow and the turn would otherwise wait for good.
 */
final class RuntimeChatTurn implements ChatTurnHandle {

    private final AgentRuntime runtime;
    private final SessionId sessionId;
    private final TurnId turnId;
    private final CompletableFuture<TurnResult> outcome = new CompletableFuture<>();

    /** Submits the message as a turn of the session and follows it. */
    RuntimeChatTurn(AgentRuntime runtime, ai.mindconnect.agent.runtime.api.domain.SessionId session, String message) {
        this.runtime = runtime;
        this.sessionId = SessionId.of(session.value());
        // Followed before the turn starts: that it asks is told by its session, and only once.
        CompletableFuture<TurnId> started = new CompletableFuture<>();
        Subscription asking = runtime.sessions().subscribe(session, false, event -> {
            if (event instanceof SessionEvent.TurnInterrupted interrupted
                    && interrupted.turnId().equals(started.join())) {
                outcome.complete(TurnResult.incomplete(ChatTurnId.of(interrupted.turnId().value()),
                        approvals(interrupted.interruptions())));
            }
        });
        this.turnId = runtime.turns().submit(TurnRequest.text(session, message));
        started.complete(turnId);
        Subscription following = runtime.turns().subscribe(turnId, new TurnListener() {
            @Override
            public void onEvent(TurnEvent event) {
            }

            @Override
            public void onFinished(Turn turn) {
                ended(turn);
            }
        });
        // A turn that ended before anyone listened is asked how it stands.
        runtime.turns().find(turnId).filter(turn -> turn.status().isFinal()).ifPresent(this::ended);
        outcome.whenComplete((result, failure) -> {
            following.close();
            asking.close();
        });
    }

    private void ended(Turn turn) {
        if (turn.status() == ai.mindconnect.agent.runtime.api.domain.TurnStatus.COMPLETED && turn.outcome() != null) {
            outcome.complete(TurnResult.completed(ChatTurnId.of(turn.id().value()),
                    turn.outcome().text() == null ? "" : turn.outcome().text()));
        } else {
            outcome.completeExceptionally(new IllegalStateException(turn.error() != null ? turn.error()
                    : "the turn ended " + turn.status().name().toLowerCase(java.util.Locale.ROOT)));
        }
    }

    private List<ToolApproval> approvals(List<Interruption> interruptions) {
        return interruptions.stream()
                .filter(open -> open.call() != null)
                .map(open -> new ToolApproval(open.id(), open.call().callId(), open.call().toolName(), "{}",
                        SessionId.of(open.call().sessionId().value()), sessionId, null, null))
                .toList();
    }

    @Override
    public ChatTurnId id() {
        return ChatTurnId.of(turnId.value());
    }

    @Override
    public SessionId sessionId() {
        return sessionId;
    }

    @Override
    public TurnStatus status() {
        if (!outcome.isDone()) {
            return TurnStatus.RUNNING;
        }
        return outcome.isCompletedExceptionally() ? TurnStatus.FAILED : outcome.join().status();
    }

    @Override
    public CompletableFuture<String> result() {
        return outcome.thenApply(TurnResult::text);
    }

    @Override
    public CompletableFuture<TurnResult> outcome() {
        return outcome;
    }

    @Override
    public boolean cancel() {
        runtime.turns().cancel(turnId);
        return true;
    }
}
