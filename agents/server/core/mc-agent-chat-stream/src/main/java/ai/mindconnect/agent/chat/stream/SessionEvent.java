package ai.mindconnect.agent.chat.stream;

import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.agent.chat.stream.StreamEvent;


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
public record SessionEvent(ChatTurnId turnId, int run, StreamEvent event) {
}
