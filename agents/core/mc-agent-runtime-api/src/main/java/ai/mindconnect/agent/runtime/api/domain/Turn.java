package ai.mindconnect.agent.runtime.api.domain;

import ai.mindconnect.agent.SessionId;

import java.util.List;

import ai.mindconnect.agent.loop.domain.TurnOutcome;

/**
 * A turn as it stands — data, not a handle: whoever wants to follow it asks
 * the runtime again by {@link #id} or subscribes to its events.
 *
 * @param parent        the turn that called this one as a sub-agent; {@code null}
 *                      for a turn a caller submitted
 * @param agent         the agent as the turn runs it, resolved when the turn
 *                      was accepted and kept: a definition changed meanwhile
 *                      does not change a turn underway. Its model settings
 *                      are the ones the turn uses — request, session and
 *                      agent laid over each other
 * @param interruptions what the turn has to ask before it can go on — its
 *                      own and those of the sub-agent turns under it. Not
 *                      empty exactly when the status is
 *                      {@link TurnStatus#INTERRUPTED}
 * @param outcome       how the turn ended; {@code null} until it has
 * @param error         why the turn failed; {@code null} unless it did
 */
public record Turn(
        TurnId id,
        TurnId parent,
        SessionId sessionId,
        AgentDefinition agent,
        TurnStatus status,
        List<Interruption> interruptions,
        TurnOutcome outcome,
        String error
) {

    public Turn {
        interruptions = List.copyOf(interruptions);
    }
}
