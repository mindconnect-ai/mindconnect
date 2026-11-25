package ai.mindconnect.agent.runtime.engine.service;

import java.util.List;
import ai.mindconnect.agent.loop.domain.TurnInterruptionEvent;

import ai.mindconnect.agent.loop.domain.TurnOutcome;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.loop.domain.TurnStreamEvent;
import ai.mindconnect.agent.loop.domain.StreamEvent;
import ai.mindconnect.agent.runtime.api.domain.SessionStreamEvent;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;

/**
 * Puts one turn on its session's stream: what the agent loop reports of it
 * goes there as it is, and around it what only the engine knows — that the
 * turn is a sub-agent's, that it stops to ask, how it ended. One object per
 * execution of a turn.
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

    /** One event of the loop: it is the session's as it is. */
    void report(TurnStreamEvent event) {
        boolean begins = event instanceof TurnStreamEvent.AskingLlm asking && !asking.resumed();
        if (begins && turn.parent() != null) {
            publish(new SessionStreamEvent.SubAgentStarted(turn.agent().name()));
        }
        publish(event);
    }

    /** The turn came to its end, and its task says so: what whoever follows it is told last. */
    void ended(TurnStatus status, TurnOutcome outcome) {
        String text = outcome.text() == null ? "" : outcome.text();
        if (turn.parent() != null) {
            publish(new SessionStreamEvent.SubAgentDone(turn.agent().name(), text));
        }
        publish(new SessionStreamEvent.Done(status, text,
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
        return SessionEvent.of(session.id(), turn.id(), turn.parent(),
                new SessionStreamEvent.Error(status, message != null ? message
                        : "The turn " + (status == TurnStatus.CANCELLED ? "was cancelled" : "failed")));
    }

    /** The turn came back with something to ask: one event for each thing it asks, then that it waits. */
    void interrupted(List<Interruption> interruptions) {
        for (Interruption asked : interruptions) {
            boolean forCall = asked.call() != null;
            if (Interruption.TOOL_APPROVAL.equals(asked.kind()) && forCall) {
                publish(new TurnInterruptionEvent.ApprovalRequested(asked.id(),
                        asked.call().callId(), asked.call().toolName(), asked.call().arguments(),
                        asked.call().sessionId().value()));
            } else {
                publish(new TurnInterruptionEvent.InputRequested(asked.id(), asked.kind(),
                        forCall ? asked.call().callId() : null, forCall ? asked.call().toolName() : null,
                        asked.details()));
            }
        }
        publish(new SessionStreamEvent.Waiting());
    }

    private void publish(StreamEvent event) {
        hub.publish(session, turns, SessionEvent.of(session.id(), turn.id(), turn.parent(), event));
    }
}
