package ai.mindconnect.agentrest.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.InterruptionAnswer;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The turns of a managed session, as the REST API and the chat page address
 * them: by the session, and an approval by its tool call. The runtime
 * addresses a turn by its own id and a question by the interruption's; this
 * is where the one is looked up from the other.
 */
@Service
public class SessionTurns {

    private final ObjectProvider<TurnService> turns;
    private final ObjectProvider<SessionStore> sessions;
    private final ObjectProvider<SessionManagement> management;

    public SessionTurns(ObjectProvider<TurnService> turns, ObjectProvider<SessionStore> sessions,
                        ObjectProvider<SessionManagement> management) {
        this.turns = turns;
        this.sessions = sessions;
        this.management = management;
    }

    /** Starts a turn in the session, as the agent the session has right now. */
    public TurnId submit(SessionId sessionId, List<ContentPart> input) {
        // A stored agent the session refers to holds as edited since.
        AgentDefinition agent = management.getObject().agentOf(sessionId).orElseThrow(() ->
                new IllegalStateException("Session " + sessionId.value() + " has no agent"));
        // Kept as the session's too: what works for the session between turns has a model to ask.
        sessions.getObject().update(sessionId, session -> session.withAgent(agent));
        return turns.getObject().submit(new TurnRequest(sessionId, input, agent, Map.of()));
    }

    /** Whether there was a turn to cancel. */
    public boolean cancel(SessionId sessionId) {
        Optional<Turn> running = turns.getObject().runningTurn(sessionId);
        running.ifPresent(turn -> turns.getObject().cancel(turn.id()));
        return running.isPresent();
    }

    /** What the session's turn waits for: its own questions and those of the sub-agents under it. */
    public List<Interruption> open(SessionId sessionId) {
        return turns.getObject().runningTurn(sessionId).map(Turn::interruptions).orElse(List.of());
    }

    /**
     * Answers the approval a tool call waits for, and the turn goes on.
     *
     * @param forSession with {@code approved}: the tool may run for the rest of the session
     * @return whether the answer reached a waiting call; false for a card that is stale
     */
    public boolean answerApproval(SessionId sessionId, String callId, boolean approved, boolean forSession) {
        Optional<Turn> running = turns.getObject().runningTurn(sessionId);
        Optional<String> asked = running.map(Turn::interruptions).orElse(List.of()).stream()
                .filter(interruption -> interruption.call() != null && callId.equals(interruption.call().callId()))
                .map(Interruption::id)
                .findFirst();
        asked.ifPresent(id -> turns.getObject().resume(running.orElseThrow().id(), List.of(
                !approved ? InterruptionAnswer.reject(id, "")
                        : forSession ? InterruptionAnswer.approveAlways(id) : InterruptionAnswer.approve(id))));
        return asked.isPresent();
    }
}
