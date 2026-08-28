package ai.mindconnect.agent.service.stream;

import ai.mindconnect.agent.domain.StreamEvent;

import java.util.UUID;

/**
 * What travels on a session's channel: the event plus its origin in the
 * session's turn tree. The envelope carries the coordinates — the
 * {@link StreamEvent} vocabulary itself stays untouched.
 *
 * <p>{@code turnId} names the logical turn, {@code run} the execution
 * attempt (a resume after a crash or an approval counts up) — together they
 * let a subscriber filter one turn out of the shared stream, or notice that
 * a partial answer restarted.
 */
public record SessionEvent(UUID turnId, int run, StreamEvent event) {
}
