package ai.mindconnect.agent.port.in;

import ai.mindconnect.agent.domain.TurnStatus;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Handle to one in-flight or finished chat turn.
 *
 * <p>Returned by {@link AgentChatClient#send}. The turn runs
 * asynchronously on the runtime's executor; the caller controls observation
 * and lifecycle through this handle.
 *
 * <p>Cancellation surfaces as a {@link java.util.concurrent.CancellationException}
 * via {@link #result()} — consistent with the {@link CompletableFuture}
 * contract. There is no separate {@code CANCELLED} status.
 */
public interface ChatTurnHandle {

    UUID id();

    UUID sessionId();

    /** Current lifecycle status of the turn. */
    TurnStatus status();

    /**
     * Future that completes with the final assistant message when the turn
     * reaches {@link TurnStatus#COMPLETED}, or completes exceptionally on
     * failure or cancellation.
     */
    CompletableFuture<String> result();

    /**
     * Cooperatively cancels the turn. Sets the turn's cancel flag, which is
     * checked between tool rounds and propagates to any live sub-agent turns.
     * Does not interrupt an in-flight LLM call — the current LLM response
     * will finish streaming before the loop exits.
     *
     * @return {@code true} if the turn was still running and was signalled,
     *         {@code false} if it had already terminated
     */
    boolean cancel();
}
