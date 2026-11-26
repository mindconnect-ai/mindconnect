package ai.mindconnect.agent.runtime.api.domain;

import java.time.Instant;

import ai.mindconnect.agent.loop.domain.TurnMessage;

/**
 * A message of a session with what the store knows beyond what a turn
 * wrote: where it stands and when it was kept. Whoever shows a session's
 * history, or removes part of it, says which messages by their place.
 *
 * @param sequence its place in the session, counting from 1; a place that
 *                 was removed is not given again
 * @param storedAt when the store kept it; {@code null} for a store that
 *                 does not note it
 */
public record StoredMessage(TurnMessage message, long sequence, Instant storedAt) { }
