package ai.mindconnect.agent.runtime.engine.service;

import java.util.List;

import ai.mindconnect.agent.loop.domain.TurnEvent;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.TurnOutcome;
import ai.mindconnect.agent.runtime.api.domain.EventType;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.StreamEvent;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;

/**
 * Turns what the agent loop reports about one turn into the events of its
 * session's stream: the loop speaks of deltas and appended messages, a
 * session of an answer being written, tool calls with their results, a
 * question, an end. One object per execution of a turn.
 *
 * <p>The loop's own events stay here: what leaves is a {@link SessionEvent}
 * of one of the kinds of {@link StreamEvent}.
 */
final class SessionEventTranslator {

    private final SessionEventHub hub;
    private final Session session;
    private final Turn turn;
    /** The turn and the tasks it runs under, nearest first. */
    private final List<TurnId> turns;

    SessionEventTranslator(SessionEventHub hub, Session session, Turn turn, List<TurnId> turns) {
        this.hub = hub;
        this.session = session;
        this.turn = turn;
        this.turns = List.copyOf(turns);
    }

    /** One event of the loop, as the events of the session it amounts to. */
    void translate(TurnEvent event) {
        switch (event) {
            case TurnEvent.TurnStarted started -> {
                if (turn.parent() != null) {
                    publish(StreamEvent.SUB_AGENT_STARTED, new StreamEvent.SubAgentStarted(turn.agent().name()));
                }
                publish(StreamEvent.ASKING_LLM, new StreamEvent.AskingLlm(false));
            }
            case TurnEvent.TurnResumed resumed -> publish(StreamEvent.ASKING_LLM, new StreamEvent.AskingLlm(true));
            case TurnEvent.TextDelta delta -> publish(StreamEvent.TOKEN, new StreamEvent.Token(delta.text()));
            case TurnEvent.ReasoningDelta reasoning ->
                    publish(StreamEvent.THINKING, new StreamEvent.Thinking(reasoning.text()));
            case TurnEvent.MessageAppended appended -> translate(appended.message());
            case TurnEvent.TurnEnded ended -> {
                // A turn that waits for its tools has not ended; one that has is told of by ended().
                if (ended.outcome().status() != TurnOutcome.Status.WAITING_FOR_TOOLS) {
                    publish(StreamEvent.TURN_USAGE, new StreamEvent.TurnUsage(
                            ended.outcome().usage().inputTokens(), ended.outcome().usage().outputTokens()));
                }
            }
            // A plain wait for tools is nothing a session reports.
            case TurnEvent.TurnWaiting waiting -> { }
        }
    }

    /** The turn came to its end, and its task says so: what whoever follows it is told last. */
    void ended(TurnStatus status, TurnOutcome outcome) {
        String text = outcome.text() == null ? "" : outcome.text();
        if (turn.parent() != null) {
            publish(StreamEvent.SUB_AGENT_DONE, new StreamEvent.SubAgentDone(turn.agent().name(), text));
        }
        publish(StreamEvent.DONE, new StreamEvent.Done(status, text,
                outcome.incompleteReason() == null ? null : outcome.incompleteReason().name()));
    }

    /**
     * The turn is over although the loop never said so — it failed, or a
     * cancel cut it off in the middle of a model call.
     */
    void brokeOff(TurnStatus status, String message) {
        hub.publish(session, turns, brokenOff(session, turn, status, message));
    }

    /** As {@link #brokeOff}, for a turn no worker is left to speak for. */
    static SessionEvent brokenOff(Session session, Turn turn, TurnStatus status, String message) {
        return SessionEvent.of(StreamEvent.ERROR, session.id(), turn.id(), turn.parent(),
                new StreamEvent.Error(status, message != null ? message
                        : "The turn " + (status == TurnStatus.CANCELLED ? "was cancelled" : "failed")));
    }

    /** The turn came back with something to ask: one event for each thing it asks, then that it waits. */
    void interrupted(List<Interruption> interruptions) {
        for (Interruption asked : interruptions) {
            boolean forCall = asked.call() != null;
            if (Interruption.TOOL_APPROVAL.equals(asked.kind()) && forCall) {
                publish(StreamEvent.APPROVAL_REQUESTED, new StreamEvent.ApprovalRequested(asked.id(),
                        asked.call().callId(), asked.call().toolName(), asked.call().arguments(),
                        asked.call().sessionId()));
            } else {
                publish(StreamEvent.INPUT_REQUESTED, new StreamEvent.InputRequested(asked.id(), asked.kind(),
                        forCall ? asked.call().callId() : null, forCall ? asked.call().toolName() : null,
                        asked.details()));
            }
        }
        publish(StreamEvent.WAITING, new StreamEvent.Waiting());
    }

    private void translate(TurnMessage message) {
        switch (message.type()) {
            case TOOL_CALL -> {
                List<TurnMessage.ToolCall> calls = message.toolCalls();
                for (int i = 0; i < calls.size(); i++) {
                    TurnMessage.ToolCall call = calls.get(i);
                    publish(StreamEvent.TOOL_CALL_STARTED,
                            new StreamEvent.ToolCallStarted(call.callId(), call.name(), call.arguments(), i));
                }
            }
            case TOOL_RESULT -> {
                String callId = String.valueOf(message.metadata().get("callId"));
                String toolName = String.valueOf(message.metadata().getOrDefault("toolName", "tool"));
                if (Boolean.TRUE.equals(message.metadata().get("failed"))) {
                    publish(StreamEvent.TOOL_CALL_FAILED,
                            new StreamEvent.ToolCallFailed(callId, toolName, message.text()));
                } else {
                    publish(StreamEvent.TOOL_CALL_RESULT,
                            new StreamEvent.ToolCallResult(callId, toolName, message.text()));
                }
            }
            default -> { }
        }
        publish(StreamEvent.MESSAGE, new StreamEvent.MessageWritten(message));
    }

    private <T> void publish(EventType<T> type, T payload) {
        hub.publish(session, turns, SessionEvent.of(type, session.id(), turn.id(), turn.parent(), payload));
    }
}
