package ai.mindconnect.cli.agentclient;

import ai.mindconnect.cli.agentclient.StreamEvent;
import ai.mindconnect.agent.loop.domain.TurnEvent;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.TurnOutcome;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * What one turn of the runtime does, as the stream events the CLI draws: the
 * turn's own, and under it those of the sub-agents it starts. A sub-agent
 * shows one level below the turn, however deep it is nested — the same the
 * server's stream tells a remote CLI.
 */
final class TurnStreamEvents {

    private final TurnId turnId;
    private final TurnService turns;
    private final Consumer<StreamEvent> events;
    /** The sub-agents seen so far, by the id of their turn. */
    private final Map<String, SubAgent> subAgents = new ConcurrentHashMap<>();

    private record SubAgent(UUID taskId, String agentName, ai.mindconnect.agent.SessionId sessionId) { }

    TurnStreamEvents(TurnId turnId, TurnService turns, Consumer<StreamEvent> events) {
        this.turnId = turnId;
        this.turns = turns;
        this.events = events;
    }

    void onEvent(TurnEvent event) {
        String from = requestId(event);
        if (turnId.value().equals(from)) {
            ownEvent(event);
        } else {
            subAgentEvent(from, event);
        }
    }

    private void ownEvent(TurnEvent event) {
        if (event instanceof TurnEvent.TurnEnded ended) {
            if (ended.outcome().status() != TurnOutcome.Status.WAITING_FOR_TOOLS) {
                events.accept(new StreamEvent.TurnUsage(
                        ended.outcome().usage().inputTokens(), ended.outcome().usage().outputTokens()));
                events.accept(new StreamEvent.Done());
            }
            return;
        }
        said(event).ifPresent(events);
    }

    private void subAgentEvent(String from, TurnEvent event) {
        SubAgent subAgent = subAgents.computeIfAbsent(from, id -> {
            Optional<Turn> turn = turns.find(TurnId.of(id));
            SubAgent seen = new SubAgent(UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)),
                    turn.map(found -> found.agent().name()).orElse("agent"),
                    turn.map(found -> found.sessionId()).orElse(null));
            events.accept(new StreamEvent.SubAgentStarted(seen.taskId(), seen.agentName(), 1, seen.sessionId(), null));
            return seen;
        });
        if (event instanceof TurnEvent.TurnEnded ended) {
            if (ended.outcome().status() != TurnOutcome.Status.WAITING_FOR_TOOLS) {
                events.accept(new StreamEvent.SubAgentDone(subAgent.taskId(), subAgent.agentName(),
                        subAgent.sessionId(), ended.outcome().text() == null ? "" : ended.outcome().text()));
            }
            return;
        }
        said(event).ifPresent(inner -> events.accept(new StreamEvent.SubAgentEvent(subAgent.taskId(), inner)));
    }

    private static Optional<StreamEvent> said(TurnEvent event) {
        return switch (event) {
            case TurnEvent.TurnStarted started -> Optional.of(new StreamEvent.AskingLlm());
            case TurnEvent.TurnResumed resumed -> Optional.of(new StreamEvent.AskingLlm());
            case TurnEvent.TextDelta text -> Optional.of(new StreamEvent.Token(text.text()));
            case TurnEvent.MessageAppended appended -> toolEvent(appended.message());
            default -> Optional.empty();
        };
    }

    private static Optional<StreamEvent> toolEvent(TurnMessage message) {
        return switch (message.type()) {
            // One line per call; a message with several calls announces the first,
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
