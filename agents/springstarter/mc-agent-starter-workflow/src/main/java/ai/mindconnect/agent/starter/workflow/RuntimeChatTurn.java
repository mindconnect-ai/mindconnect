package ai.mindconnect.agent.starter.workflow;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.StreamEvent;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.agent.tools.workflow.WorkflowAgentCalls;

/**
 * A turn of the runtime as an agent-call step waits for it: its first
 * outcome is the answer, the questions it stopped for, or its failure. The
 * step is told about a stop as well as about an end, because nobody answers
 * an approval inside a workflow and the turn would otherwise wait for good.
 */
final class RuntimeChatTurn implements WorkflowAgentCalls.PendingAnswer {

    private final AgentRuntime runtime;
    private final TurnId turnId;
    private final CompletableFuture<WorkflowAgentCalls.Outcome> outcome = new CompletableFuture<>();

    /** Submits the message as a turn of the session and follows it. */
    RuntimeChatTurn(AgentRuntime runtime, SessionId session, String message) {
        this.runtime = runtime;
        this.turnId = runtime.turns().submit(TurnRequest.text(session, message));
        // The tools the turn, or a turn it started, asks an approval for at this stop.
        List<String> toolsWaiting = new CopyOnWriteArrayList<>();
        Subscription following = runtime.turns().subscribe(turnId, event -> {
            boolean own = turnId.equals(event.turnId());
            switch (event.known()) {
                case StreamEvent.ApprovalRequested asked -> toolsWaiting.add(asked.toolName());
                case StreamEvent.InputRequested asked -> {
                    if (asked.toolName() != null) {
                        toolsWaiting.add(asked.toolName());
                    }
                }
                case StreamEvent.Waiting waiting ->
                        outcome.complete(new WorkflowAgentCalls.Outcome(null, List.copyOf(toolsWaiting)));
                case StreamEvent.Done done when own -> {
                    if (done.status() == TurnStatus.COMPLETED) {
                        outcome.complete(new WorkflowAgentCalls.Outcome(
                                done.text() == null ? "" : done.text(), List.of()));
                    } else {
                        outcome.completeExceptionally(new IllegalStateException(
                                "the turn ended " + done.status().name().toLowerCase(Locale.ROOT)));
                    }
                }
                case StreamEvent.Error error when own ->
                        outcome.completeExceptionally(new IllegalStateException(error.message()));
                default -> { }
            }
        });
        outcome.whenComplete((result, failure) -> following.close());
    }

    @Override
    public CompletableFuture<WorkflowAgentCalls.Outcome> outcome() {
        return outcome;
    }

    @Override
    public void cancel() {
        runtime.turns().cancel(turnId);
    }
}
