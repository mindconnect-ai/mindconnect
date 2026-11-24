package ai.mindconnect.agent.runtime.api.port.in;

import java.util.List;
import java.util.Optional;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.InterruptionAnswer;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;

/**
 * Turns, without a handle: a turn is started, watched, resumed and
 * cancelled by its id, from any process — the one that started it need not
 * be the one that watches it.
 */
public interface TurnService {

    /**
     * Accepts the turn and returns at once with its id; the turn runs in the
     * background. Nothing else comes back: how the turn stands, as which
     * agent it runs and how it ended is asked with {@link #find}, what
     * happens in it is followed with {@link #subscribe}.
     *
     * <p>What can be known at once is refused at once: a session or an agent
     * that does not exist is an exception here, not a failed turn later.
     */
    TurnId submit(TurnRequest request);

    /**
     * The turn as it stands now — its status while it runs, the
     * interruptions it came back with, its outcome once it is over. Empty
     * for an id no turn was accepted under.
     */
    Optional<Turn> find(TurnId turnId);

    /**
     * The turn of this session that has not ended: the one that runs, waits
     * for its tools or has come back interrupted. Turns submitted after it
     * stand in line behind it and are not it. Empty when the session is idle.
     *
     * <p>Only turns submitted to the session count. The turn of a sub-agent
     * is found through the turn that called it ({@link Turn#parent}).
     */
    Optional<Turn> runningTurn(SessionId sessionId);

    /**
     * What the turn has written into its session so far, oldest first — the
     * user's message that opened it, its tool calls and results, its answer.
     * A sub-agent's messages are its own turn's, in its own session, and
     * not among these. Empty for an id no turn was accepted under.
     */
    List<TurnMessage> messages(TurnId turnId);

    /**
     * Pushes on a turn that came back interrupted: the answered calls run,
     * are refused or get the answer as their result, and the turn goes on in
     * the background as after {@link #submit}. Interruptions left unanswered
     * stay open, and the turn comes back with them again.
     *
     * @param answers answers to interruptions of {@link Turn#interruptions()};
     *                one for an interruption the turn does not have is
     *                refused
     */
    void resume(TurnId turnId, List<InterruptionAnswer> answers);

    /**
     * Follows the turn until the subscription is closed or the turn is over.
     * A listener that comes late is first told what the runtime still keeps
     * of the turn, so subscribing right after {@link #submit} loses nothing;
     * one that comes long after may hear only the rest. That the turn is
     * over it is told in any case, and last. What the turn said is in the
     * session whenever it is asked for.
     *
     * <p>The listener is told on a thread of its own, in the order things
     * happened: a listener that is slow holds up nobody but itself.
     */
    Subscription subscribe(TurnId turnId, TurnListener listener);

    /** Tells the turn to stop; it ends cancelled at the next point it can. */
    void cancel(TurnId turnId);
}
