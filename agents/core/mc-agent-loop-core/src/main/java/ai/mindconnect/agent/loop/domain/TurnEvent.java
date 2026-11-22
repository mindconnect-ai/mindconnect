package ai.mindconnect.agent.loop.domain;

import java.util.Set;

/**
 * What happens in a turn, as it happens — for whoever watches: a client's
 * stream, a trace, a progress display. Observation only: nothing a watcher
 * does with an event changes the turn.
 *
 * <p>Two kinds travel on the same channel. What the loop reports is durable:
 * a {@link MessageAppended} is announced after the message is stored, so a
 * watcher never sees something a reload would not show. What the model call
 * reports is not: {@link TextDelta} and {@link ReasoningDelta} are fragments
 * of an answer still being written, and the message that finally holds them
 * follows as a {@link MessageAppended}.
 */
public sealed interface TurnEvent {

    /** The request the event belongs to — one listener may watch several turns. */
    String requestId();

    /** The turn began: the user's message is the next thing appended. */
    record TurnStarted(String requestId) implements TurnEvent { }

    /** The turn goes on after a wait; the calls it waited for may be done. */
    record TurnResumed(String requestId) implements TurnEvent { }

    /** A message is in the conversation now — the stored one, with its id. */
    record MessageAppended(String requestId, TurnMessage message) implements TurnEvent { }

    /** A fragment of the answer text the model is writing. */
    record TextDelta(String requestId, String text) implements TurnEvent { }

    /** A fragment of the model's readable reasoning. */
    record ReasoningDelta(String requestId, String text) implements TurnEvent { }

    /** The turn stopped to wait for these tool calls; a resume follows. */
    record TurnWaiting(String requestId, Set<String> waitingFor) implements TurnEvent { }

    /** The turn is over — completed, incomplete or cancelled. */
    record TurnEnded(String requestId, TurnOutcome outcome) implements TurnEvent { }
}
