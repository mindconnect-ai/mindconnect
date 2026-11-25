package ai.mindconnect.agentrest.stream;

import java.nio.charset.StandardCharsets;
import ai.mindconnect.agent.loop.domain.TurnInterruptionEvent;
import java.util.Optional;
import java.util.UUID;

import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.loop.domain.TurnStreamEvent;
import ai.mindconnect.agent.runtime.api.domain.SessionStreamEvent;
import ai.mindconnect.agent.runtime.api.domain.TurnId;

/**
 * An event of a turn as the frame a client streams — one frame, or none for
 * what a client is not shown. Nothing is remembered between two events: the
 * event says itself which turn it is from.
 *
 * <p>A client shows the turns a turn starts as a tree, so what a sub-agent
 * does is wrapped as an event of that sub-agent, under the task it runs as.
 * Every sub-agent is shown one level below the turn.
 */
public final class TurnFrameTranslator {

    private final TurnId turnId;

    /** @param turnId the turn the frames are of; every other turn's events are a sub-agent's */
    public TurnFrameTranslator(TurnId turnId) {
        this.turnId = turnId;
    }

    public Optional<TurnFrame> frame(SessionEvent event) {
        boolean own = turnId.equals(event.turnId());
        String task = event.turnId() == null ? null : taskId(event.turnId());
        String session = event.sessionId().value();
        return switch (event.known()) {
            case TurnStreamEvent.AskingLlm asking -> shown(own, task, TurnFrame.askingLlm());
            case TurnStreamEvent.TextDelta token -> shown(own, task, TurnFrame.token(token.text()));
            case TurnStreamEvent.Thinking thinking -> shown(own, task, TurnFrame.thinking(thinking.text()));
            // A client draws one card per request; a request for several calls announces the first,
            // the rest show with their results.
            case TurnStreamEvent.ToolCallStarted call -> call.index() > 0 ? Optional.empty()
                    : shown(own, task, TurnFrame.toolCallStarted(call.toolName(), call.arguments()));
            case TurnStreamEvent.ToolCallResult result ->
                    shown(own, task, TurnFrame.toolCallResult(result.toolName(), result.result(), 0));
            case TurnStreamEvent.ToolCallFailed failed ->
                    shown(own, task, TurnFrame.toolCallFailed(failed.toolName(), failed.error(), 0));
            // Whoever asks, the question is the turn's: the card stands in the chat of the turn.
            case TurnInterruptionEvent.ApprovalRequested asked -> Optional.of(TurnFrame.approvalRequested(asked.requestId(),
                    asked.callId(), asked.toolName(), asked.arguments(),
                    asked.originSessionId() == null ? session : asked.originSessionId()));
            case TurnStreamEvent.TurnUsage usage -> own
                    ? Optional.of(TurnFrame.turnUsage(usage.inputTokens(), usage.outputTokens())) : Optional.empty();
            case SessionStreamEvent.Done done -> own ? Optional.of(TurnFrame.done()) : Optional.empty();
            case SessionStreamEvent.SubAgentStarted started -> own ? Optional.empty()
                    : Optional.of(TurnFrame.subAgentStarted(task, started.agentName(), 1, session));
            case SessionStreamEvent.SubAgentDone ended -> own ? Optional.empty()
                    : Optional.of(TurnFrame.subAgentDone(task, ended.agentName(), session, ended.finalText()));
            // How a turn broke off is told where its stream ends; the rest — a question that is no
            // approval, a whole message, a plugin's event — is nothing a client streams.
            default -> Optional.empty();
        };
    }

    private static Optional<TurnFrame> shown(boolean own, String task, TurnFrame frame) {
        return Optional.of(own ? frame : TurnFrame.subAgentEvent(task, frame));
    }

    /** The task a sub-agent's turn is shown as: the same for every event of that turn. */
    private static String taskId(TurnId turnId) {
        return UUID.nameUUIDFromBytes(turnId.value().getBytes(StandardCharsets.UTF_8)).toString();
    }
}
