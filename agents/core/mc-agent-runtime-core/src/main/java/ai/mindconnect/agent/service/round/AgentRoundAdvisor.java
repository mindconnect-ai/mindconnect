package ai.mindconnect.agent.service.round;

import ai.mindconnect.message.domain.Message;

import java.util.List;
import java.util.UUID;

/**
 * Turn-level policy around a single round — the same in-band pattern as
 * {@code TaskAdvisor} and {@code ToolAdvisor} (concept 1: observation may
 * never block, policy may never be asynchronous; this is policy).
 *
 * <p>Two hooks, because a round has two moments policy cares about:
 *
 * <ul>
 *   <li>{@link #aroundRound} wraps the round's execution and may rewrite its
 *       outcome — the reviewer chain rewrites the final answer here, BEFORE
 *       anything is persisted, so the conversation only ever holds the
 *       reviewed text. Exceptions are real: they fail the turn.</li>
 *   <li>{@link #afterRoundPersisted} sees what the round's messages became
 *       once they are durable — where tool-result compression hooks in.
 *       Exceptions are logged and swallowed: nothing after persistence may
 *       cost the turn.</li>
 * </ul>
 *
 * <p>Advisors run in list order; {@code aroundRound} wraps like an onion
 * (first advisor outermost).
 */
public interface AgentRoundAdvisor {

    /** The wrapped continuation — the round itself, or the next advisor in. */
    @FunctionalInterface
    interface Execution {
        RoundOutcome proceed();
    }

    /** What an advisor may know about the round it wraps. */
    record RoundContext(String requestId, UUID conversationId, UUID sessionId,
                        List<Message> history) { }

    default RoundOutcome aroundRound(RoundContext context, Execution execution) {
        return execution.proceed();
    }

    default void afterRoundPersisted(RoundContext context, List<Message> persisted) {
    }
}
