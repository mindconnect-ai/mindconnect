package ai.mindconnect.cli.agentclient;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.TurnId;

/**
 * An event of a turn of the runtime as the stream event the CLI draws — one,
 * or none for what the CLI does not show. What a sub-agent does shows one
 * level below the turn, however deep it is nested — the same the server's
 * stream tells a remote CLI. Nothing is remembered between two events.
 */
final class TurnStreamEvents {

    private final TurnId turnId;

    /** @param turnId the turn the CLI draws; every other turn's events are a sub-agent's */
    TurnStreamEvents(TurnId turnId) {
        this.turnId = turnId;
    }

    Optional<StreamEvent> drawn(SessionEvent event) {
        boolean own = turnId.equals(event.turnId());
        UUID task = event.turnId() == null ? null
                : UUID.nameUUIDFromBytes(event.turnId().value().getBytes(StandardCharsets.UTF_8));
        return switch (event.known()) {
            case ai.mindconnect.agent.loop.domain.TurnStreamEvent.AskingLlm asking ->
                    shown(own, task, new StreamEvent.AskingLlm());
            case ai.mindconnect.agent.loop.domain.TurnStreamEvent.TextDelta token ->
                    shown(own, task, new StreamEvent.Token(token.text()));
            // One line per request; a request for several calls announces the first,
            // the rest show with their results.
            case ai.mindconnect.agent.loop.domain.TurnStreamEvent.ToolCallStarted call -> call.index() > 0
                    ? Optional.empty()
                    : shown(own, task, new StreamEvent.ToolCallStarted(call.toolName(), call.arguments()));
            case ai.mindconnect.agent.loop.domain.TurnStreamEvent.ToolCallResult result ->
                    shown(own, task, new StreamEvent.ToolCallResult(result.toolName(), result.result(), 0));
            case ai.mindconnect.agent.loop.domain.TurnStreamEvent.ToolCallFailed failed ->
                    shown(own, task, new StreamEvent.ToolCallFailed(failed.toolName(), failed.error(), 0));
            // Whoever asks, the question is drawn with the turn: its user answers it.
            case ai.mindconnect.agent.loop.domain.TurnInterruptionEvent.ApprovalRequested asked ->
                    Optional.of(new StreamEvent.ApprovalRequested(asked.requestId(), asked.callId(),
                            asked.toolName(), asked.arguments(), asked.originSessionId() == null ? null
                                    : ai.mindconnect.agent.SessionId.of(asked.originSessionId()), null));
            case ai.mindconnect.agent.loop.domain.TurnStreamEvent.TurnUsage usage -> own
                    ? Optional.of(new StreamEvent.TurnUsage(usage.inputTokens(), usage.outputTokens()))
                    : Optional.empty();
            case ai.mindconnect.agent.runtime.api.domain.SessionStreamEvent.Done done ->
                    own ? Optional.of(new StreamEvent.Done()) : Optional.empty();
            case ai.mindconnect.agent.runtime.api.domain.SessionStreamEvent.SubAgentStarted started -> own
                    ? Optional.empty()
                    : Optional.of(new StreamEvent.SubAgentStarted(task, started.agentName(), 1,
                            event.sessionId(), null));
            case ai.mindconnect.agent.runtime.api.domain.SessionStreamEvent.SubAgentDone ended -> own
                    ? Optional.empty()
                    : Optional.of(new StreamEvent.SubAgentDone(task, ended.agentName(), event.sessionId(),
                            ended.finalText()));
            default -> Optional.empty();
        };
    }

    private static Optional<StreamEvent> shown(boolean own, UUID task, StreamEvent event) {
        return Optional.of(own ? event : new StreamEvent.SubAgentEvent(task, event));
    }
}
