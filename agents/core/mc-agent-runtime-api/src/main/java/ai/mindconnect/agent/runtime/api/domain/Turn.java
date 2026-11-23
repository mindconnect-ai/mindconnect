package ai.mindconnect.agent.runtime.api.domain;

import java.util.List;

import ai.mindconnect.agent.loop.domain.TurnOutcome;

/**
 * A turn as it stands — data, not a handle: whoever wants to follow it asks
 * the runtime again by {@link #id} or subscribes to its events.
 *
 * @param agent         the agent the turn runs as, resolved when the turn
 *                      was accepted and kept: a definition changed meanwhile
 *                      does not change a turn underway
 * @param modelSettings the settings the turn uses, all layers laid over each
 *                      other, kept for the same reason
 * @param interruptions what the turn has to ask before it can go on — its
 *                      own and those of the sub-agent turns under it. Not
 *                      empty exactly when the status is
 *                      {@link TurnStatus#INTERRUPTED}
 * @param outcome       how the turn ended; {@code null} until it has
 * @param error         why the turn failed; {@code null} unless it did
 */
public record Turn(
        TurnId id,
        SessionId sessionId,
        AgentDefinition agent,
        ModelSettings modelSettings,
        TurnStatus status,
        List<Interruption> interruptions,
        TurnOutcome outcome,
        String error
) {

    public Turn {
        interruptions = List.copyOf(interruptions);
    }
}
