package ai.mindconnect.agent.starter.runtime.bridge;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.loop.domain.TurnEvent;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.TurnOutcome;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.domain.StreamEvent;

/**
 * What a turn of the runtime on the plugin kernel reports, as the stream
 * events the chat page has always been drawn from — so the page does not
 * change while the runtime under it does.
 *
 * <p>The events of a sub-agent's turn arrive on its caller's turn. The page
 * shows them as a tree, so each is wrapped as an event of that sub-agent,
 * announced when its first event is seen. Every sub-agent is shown one level
 * below the turn: the events do not say how deep it is.
 */
final class StreamEventTranslator implements TurnEventListener {

    private final TurnId turnId;
    private final TurnService turns;
    private final Consumer<StreamEvent> stream;
    private final CompletableFuture<String> answer;
    private final Map<String, SubAgent> subAgents = new ConcurrentHashMap<>();

    private record SubAgent(UUID taskId, String agentName, SessionId sessionId) { }

    /**
     * @param answer completed with the turn's answer when it ends, however it ends
     */
    StreamEventTranslator(TurnId turnId, TurnService turns, Consumer<StreamEvent> stream,
                          CompletableFuture<String> answer) {
        this.turnId = turnId;
        this.turns = turns;
        this.stream = stream;
        this.answer = answer;
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

    private void ownEvent(TurnEvent event) {
        switch (event) {
            case TurnEvent.TurnEnded ended -> {
                if (ended.outcome().status() == TurnOutcome.Status.WAITING_FOR_TOOLS) {
                    return;
                }
                stream.accept(new StreamEvent.TurnUsage(
                        ended.outcome().usage().inputTokens(), ended.outcome().usage().outputTokens()));
                stream.accept(new StreamEvent.Done());
                answer.complete(ended.outcome().text() == null ? "" : ended.outcome().text());
            }
            default -> said(event).ifPresent(stream);
        }
    }

    /** The turn has to ask before it can go on: one card per tool call that waits for an approval. */
    void interrupted(List<Interruption> interruptions) {
        interruptions.stream()
                .filter(interruption -> Interruption.TOOL_APPROVAL.equals(interruption.kind()))
                .forEach(interruption -> stream.accept(new StreamEvent.ApprovalRequested(
                        interruption.id(), interruption.call().callId(), interruption.call().toolName(),
                        interruption.call().arguments(), SessionId.of(interruption.call().sessionId().value()),
                        interruption.id())));
    }

    private void subAgentEvent(String from, TurnEvent event) {
        SubAgent subAgent = subAgents.computeIfAbsent(from, id -> {
            Optional<Turn> turn = turns.find(TurnId.of(id));
            SubAgent seen = new SubAgent(
                    UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)),
                    turn.map(found -> found.agent().name()).orElse("agent"),
                    turn.map(found -> SessionId.of(found.sessionId().value())).orElse(null));
            stream.accept(new StreamEvent.SubAgentStarted(seen.taskId(), seen.agentName(), 1, seen.sessionId(), ""));
            return seen;
        });
        if (event instanceof TurnEvent.TurnEnded ended) {
            if (ended.outcome().status() != TurnOutcome.Status.WAITING_FOR_TOOLS) {
                stream.accept(new StreamEvent.SubAgentDone(subAgent.taskId(), subAgent.agentName(),
                        subAgent.sessionId(), ended.outcome().text() == null ? "" : ended.outcome().text()));
            }
            return;
        }
        said(event).ifPresent(inner -> stream.accept(new StreamEvent.SubAgentEvent(subAgent.taskId(), inner)));
    }

    /** What an event says to the page, whoever's turn it comes from; empty for what the page does not show. */
    private static Optional<StreamEvent> said(TurnEvent event) {
        return switch (event) {
            case TurnEvent.TurnStarted started -> Optional.of(new StreamEvent.AskingLlm());
            case TurnEvent.TurnResumed resumed -> Optional.of(new StreamEvent.AskingLlm());
            case TurnEvent.TextDelta text -> Optional.of(new StreamEvent.Token(text.text()));
            case TurnEvent.ReasoningDelta reasoning -> Optional.of(new StreamEvent.Thinking(reasoning.text()));
            case TurnEvent.MessageAppended appended -> toolEvent(appended.message());
            default -> Optional.empty();
        };
    }

    private static Optional<StreamEvent> toolEvent(TurnMessage message) {
        return switch (message.type()) {
            // The page draws one card per call; a message with several calls announces the first,
            // the rest show with their results.
            case TOOL_CALL -> message.toolCalls().stream().findFirst()
                    .map(call -> new StreamEvent.ToolCallStarted(call.name(), call.arguments()));
            case TOOL_RESULT -> {
                String toolName = String.valueOf(message.metadata().getOrDefault("toolName", "tool"));
                yield Optional.of(Boolean.TRUE.equals(message.metadata().get("failed"))
                        ? new StreamEvent.ToolCallFailed(toolName, message.text(), 0)
                        : new StreamEvent.ToolCallResult(toolName, message.text(), 0));
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
