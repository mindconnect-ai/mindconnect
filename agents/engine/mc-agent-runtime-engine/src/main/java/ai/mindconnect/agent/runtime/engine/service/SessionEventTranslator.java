package ai.mindconnect.agent.runtime.engine.service;

import java.util.List;

import ai.mindconnect.agent.loop.domain.TurnEvent;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;

/**
 * Turns what the agent loop reports about one turn into what a session
 * reports: the loop speaks of deltas and appended messages, a session of
 * messages and tool calls that begin and end. One object per execution of
 * a turn — what it remembers, that an answer has begun, does not outlast
 * the model call it belongs to.
 */
final class SessionEventTranslator {

    private final SessionEventHub hub;
    private final SessionStore sessions;
    private final Session session;
    private final Turn turn;
    /** Whether the agent's answer in the making has been announced. */
    private boolean answering;

    SessionEventTranslator(SessionEventHub hub, SessionStore sessions, Session session, Turn turn) {
        this.hub = hub;
        this.sessions = sessions;
        this.session = session;
        this.turn = turn;
    }

    /** One event of the loop: as it is, and as the events of the session it amounts to. */
    void translate(TurnEvent event) {
        TurnId turnId = turn.id();
        publish(new SessionEvent.TurnReported(session.id(), turnId, event));
        switch (event) {
            case TurnEvent.TurnStarted started ->
                    publish(new SessionEvent.TurnStarted(session.id(), turnId, turn.agent().name()));
            case TurnEvent.TextDelta delta -> announceAnswer();
            case TurnEvent.MessageAppended appended -> translate(appended.message());
            case TurnEvent.TurnEnded ended -> {
                // A turn that waits for its tools has not ended.
                if (statusOf(ended).isFinal()) {
                    publish(new SessionEvent.TurnFinished(session.id(), turnId, statusOf(ended)));
                }
            }
            // A turn that goes on after a wait, the model's reasoning and a
            // plain wait for tools are nothing a session reports.
            case TurnEvent.TurnResumed resumed -> { }
            case TurnEvent.ReasoningDelta reasoning -> { }
            case TurnEvent.TurnWaiting waiting -> { }
        }
    }

    /**
     * The turn is over although the loop never said so — it failed, or a
     * cancel cut it off in the middle of a model call.
     */
    void finished(TurnStatus status) {
        publish(new SessionEvent.TurnFinished(session.id(), turn.id(), status));
    }

    /** The turn came back with something to ask. */
    void interrupted(List<Interruption> interruptions) {
        publish(new SessionEvent.TurnInterrupted(session.id(), turn.id(), interruptions));
    }

    private void translate(TurnMessage message) {
        TurnId turnId = turn.id();
        switch (message.type()) {
            case USER -> publish(new SessionEvent.MessageFinished(session.id(), turnId, message));
            case ASSISTANT -> {
                // A model that does not stream says nothing before it is
                // done; the answer still begins before it ends.
                announceAnswer();
                answering = false;
                publish(new SessionEvent.MessageFinished(session.id(), turnId, message));
            }
            // The request itself is no event: each call it asks for is
            // announced when it is taken up.
            case TOOL_CALL -> answering = false;
            case TOOL_DISPATCHED -> {
                String callId = String.valueOf(message.metadata().get("callId"));
                publish(new SessionEvent.ToolCallStarted(session.id(), turnId, callId, toolNameOf(callId)));
            }
            case TOOL_RESULT -> publish(new SessionEvent.ToolCallFinished(session.id(), turnId,
                    String.valueOf(message.metadata().get("callId")),
                    String.valueOf(message.metadata().get("toolName")),
                    Boolean.TRUE.equals(message.metadata().get("failed"))));
        }
    }

    private void announceAnswer() {
        if (!answering) {
            answering = true;
            publish(new SessionEvent.MessageStarted(session.id(), turn.id()));
        }
    }

    /** The dispatch marker names only the call; which tool it is stands in the request the model made. */
    private String toolNameOf(String callId) {
        return sessions.messages(session.id(), turn.id()).stream()
                .flatMap(message -> message.toolCalls().stream())
                .filter(call -> call.callId().equals(callId))
                .map(TurnMessage.ToolCall::name)
                .findFirst().orElse("");
    }

    private void publish(SessionEvent event) {
        hub.publish(session, event);
    }

    private static TurnStatus statusOf(TurnEvent.TurnEnded ended) {
        return switch (ended.outcome().status()) {
            case COMPLETED -> TurnStatus.COMPLETED;
            case INCOMPLETE -> TurnStatus.INCOMPLETE;
            case CANCELLED -> TurnStatus.CANCELLED;
            case WAITING_FOR_TOOLS -> TurnStatus.WAITING;
        };
    }
}
