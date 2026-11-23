package ai.mindconnect.agentrest.stream;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import ai.mindconnect.agent.loop.domain.TurnEvent;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.TurnOutcome;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;

/**
 * What a turn of the runtime reports, as the frames a client streams.
 *
 * <p>The events of a sub-agent's turn arrive on its caller's turn. A client
 * shows them as a tree, so each is wrapped as an event of that sub-agent,
 * announced when its first event is seen. Every sub-agent is shown one level
 * below the turn: the events do not say how deep it is.
 */
public final class TurnFrameTranslator implements TurnEventListener {

    private final TurnId turnId;
    private final TurnService turns;
    private final Consumer<TurnFrame> frames;
    private final Map<String, SubAgent> subAgents = new ConcurrentHashMap<>();

    private record SubAgent(String taskId, String agentName, String sessionId) { }

    public TurnFrameTranslator(TurnId turnId, TurnService turns, Consumer<TurnFrame> frames) {
        this.turnId = turnId;
        this.turns = turns;
        this.frames = frames;
    }

    @Override
    public void onEvent(TurnEvent event) {
        String from = requestId(event);
        if (turnId.value().equals(from)) {
            ownEvent(event);
        } else {
            subAgentEvent(from, event);
        }
    }

    /** The turn has to ask before it can go on: one frame per tool call that waits for an approval. */
    public void interrupted(List<Interruption> interruptions) {
        interruptions.stream()
                .filter(interruption -> Interruption.TOOL_APPROVAL.equals(interruption.kind()))
                .forEach(interruption -> frames.accept(TurnFrame.approvalRequested(interruption.id(),
                        interruption.call().callId(), interruption.call().toolName(),
                        interruption.call().arguments(), interruption.call().sessionId().value())));
    }

    private void ownEvent(TurnEvent event) {
        if (event instanceof TurnEvent.TurnEnded ended) {
            if (ended.outcome().status() != TurnOutcome.Status.WAITING_FOR_TOOLS) {
                frames.accept(TurnFrame.turnUsage(
                        ended.outcome().usage().inputTokens(), ended.outcome().usage().outputTokens()));
                frames.accept(TurnFrame.done());
            }
            return;
        }
        said(event).ifPresent(frames);
    }

    private void subAgentEvent(String from, TurnEvent event) {
        SubAgent subAgent = subAgents.computeIfAbsent(from, id -> {
            Optional<Turn> turn = turns.find(TurnId.of(id));
            SubAgent seen = new SubAgent(
                    UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString(),
                    turn.map(found -> found.agent().name()).orElse("agent"),
                    turn.map(found -> found.sessionId().value()).orElse(null));
            frames.accept(TurnFrame.subAgentStarted(seen.taskId(), seen.agentName(), 1, seen.sessionId()));
            return seen;
        });
        if (event instanceof TurnEvent.TurnEnded ended) {
            if (ended.outcome().status() != TurnOutcome.Status.WAITING_FOR_TOOLS) {
                frames.accept(TurnFrame.subAgentDone(subAgent.taskId(), subAgent.agentName(), subAgent.sessionId(),
                        ended.outcome().text() == null ? "" : ended.outcome().text()));
            }
            return;
        }
        said(event).ifPresent(inner -> frames.accept(TurnFrame.subAgentEvent(subAgent.taskId(), inner)));
    }

    /** What an event says to a client, whoever's turn it comes from; empty for what a client is not shown. */
    private static Optional<TurnFrame> said(TurnEvent event) {
        return switch (event) {
            case TurnEvent.TurnStarted started -> Optional.of(TurnFrame.askingLlm());
            case TurnEvent.TurnResumed resumed -> Optional.of(TurnFrame.askingLlm());
            case TurnEvent.TextDelta text -> Optional.of(TurnFrame.token(text.text()));
            case TurnEvent.ReasoningDelta reasoning -> Optional.of(TurnFrame.thinking(reasoning.text()));
            case TurnEvent.MessageAppended appended -> toolFrame(appended.message());
            default -> Optional.empty();
        };
    }

    private static Optional<TurnFrame> toolFrame(TurnMessage message) {
        return switch (message.type()) {
            // A client draws one card per call; a message with several calls announces the first,
            // the rest show with their results.
            case TOOL_CALL -> message.toolCalls().stream().findFirst()
                    .map(call -> TurnFrame.toolCallStarted(call.name(), call.arguments()));
            case TOOL_RESULT -> {
                String toolName = String.valueOf(message.metadata().getOrDefault("toolName", "tool"));
                yield Optional.of(Boolean.TRUE.equals(message.metadata().get("failed"))
                        ? TurnFrame.toolCallFailed(toolName, message.text(), 0)
                        : TurnFrame.toolCallResult(toolName, message.text(), 0));
            }
            default -> Optional.empty();
        };
    }

    private static String requestId(TurnEvent event) {
        return switch (event) {
            case TurnEvent.TurnStarted started -> started.requestId();
            case TurnEvent.TurnResumed resumed -> resumed.requestId();
            case TurnEvent.MessageAppended appended -> appended.requestId();
            case TurnEvent.TextDelta text -> text.requestId();
            case TurnEvent.ReasoningDelta reasoning -> reasoning.requestId();
            case TurnEvent.TurnWaiting waiting -> waiting.requestId();
            case TurnEvent.TurnEnded ended -> ended.requestId();
        };
    }
}
