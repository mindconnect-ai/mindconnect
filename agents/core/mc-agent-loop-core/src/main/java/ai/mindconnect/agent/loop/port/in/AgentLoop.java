package ai.mindconnect.agent.loop.port.in;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.TurnOutcome;

/**
 * The agent loop: runs one turn — one unit of conversation, from the user's
 * request to the model's answer or to the next thing the turn has to wait
 * for. What comes out is one {@link TurnOutcome}.
 *
 * <p>A loop holds nothing of a turn: what belongs to one comes in as its
 * {@link TurnContext}, and what the turn has done so far is in the context's
 * journal. A loop knows no task queue either. Running it in the background,
 * resuming it after a wait and starting the next turn are the caller's
 * business.
 */
public interface AgentLoop {

    /**
     * Starts the turn: appends the user's message to the conversation and
     * turns the turn to its end — or to its next wait.
     *
     * <p>Returns even when the turn is NOT finished: waiting only for a tool
     * is a {@link TurnOutcome#waitingForTools} and the caller calls
     * {@link #resume} once those calls are done. Waiting here would hold a
     * thread and all state for an unknown time — and not survive a restart.
     *
     * <p>Starting the same request again — a worker repeating its task after
     * a crash — does not append the message a second time; the turn goes on
     * from where the conversation says it is.
     *
     * @param userMessage what the user said, a {@link TurnMessage.Type#USER}
     *                    message not stored yet
     */
    TurnOutcome start(TurnContext turn, TurnMessage userMessage);

    /**
     * Continues a turn that returned waiting. Nothing is appended first and
     * nothing is handed in: the user's message, everything the turn did, its
     * rounds and their cost are in the journal, which is why resuming twice
     * is harmless.
     */
    TurnOutcome resume(TurnContext turn);

    /**
     * Cancels the turn of {@code requestId} that is underway in this loop;
     * callable from any thread. The turn ends
     * {@link TurnOutcome.Status#CANCELLED} at the next point it can: a model
     * call in flight is aborted, running tools are told to stop, and no
     * further round starts. What the turn produced until then stays in the
     * conversation.
     *
     * <p>A turn that is not running here — waiting for a tool, or running
     * elsewhere — is not reached by this call. It is cancelled through its
     * journal, which every start and resume asks first.
     */
    void cancel(String requestId);
}
