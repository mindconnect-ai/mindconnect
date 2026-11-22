package ai.mindconnect.agent.loop.port.in;

import java.util.List;

import ai.mindconnect.agent.loop.domain.RoundOutcome;
import ai.mindconnect.agent.loop.domain.ToolCalls;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;

/**
 * One turn of the agent loop's crank — one call, one round.
 *
 * <p>Who turns the rounds is {@link AgentLoop}, one level up. Here stands only
 * what ONE round does; everything between two rounds — persisting, publishing,
 * cancelling, steering, counting — belongs there.
 *
 * <p>A round holds no state: everything it needs to know is in the message
 * list that comes in — {@link ToolCalls} folds it, episode-locally. That is
 * why the caller may do whatever it wants between rounds: persist, stream,
 * compact old tool results, cancel. One rule binds the caller: <b>shorten
 * yes, remove only when no call is open</b> — openness is derived, so a
 * removed TOOL_RESULT re-opens a finished call and the tool would run again.
 */
public interface AgentRound {

    /**
     * One round over the FULL history. The fold itself is episode-local
     * ({@link ToolCalls#episode}), the model call gets everything — old calls
     * stay visible as context, only never executed again.
     *
     * <p>Returns what the round produced, unpersisted; the caller turns again
     * while {@link RoundOutcome.Outcome#continues()}.
     *
     * @param requestId identifies the whole turn (the user's request)
     * @param events    where the model call reports the answer it is receiving
     */
    RoundOutcome execute(String requestId, String sessionId, List<TurnMessage> history,
                         TurnEventListener events);

    /**
     * Stops what a round of {@code requestId} has in flight — the model call,
     * the tools it started. An {@link #execute} underway returns with what it
     * has; whether another round follows is the loop's decision, not this
     * one's.
     */
    void cancel(String requestId);
}
