package ai.mindconnect.agent.management.api;

import java.time.Instant;

import ai.mindconnect.agent.loop.domain.TurnMessage;

/**
 * A message of a session as it is stored: what a turn wrote, and what the
 * store knows about it beyond that.
 *
 * @param sequence       its place in the session, counted from the first
 *                       message — what "everything from here on" is said with
 * @param message        its kind, content, tool calls and metadata, as the
 *                       runtime wrote them
 * @param durationMillis how long producing it took; {@code null} where
 *                       nothing was measured
 * @param tokens         what it takes of a model's context; {@code null}
 *                       where it was not counted
 */
public record ManagedMessage(
        String id,
        int sequence,
        Instant sentAt,
        TurnMessage message,
        Long durationMillis,
        Integer tokens
) { }
