package ai.mindconnect.agent.domain;

/**
 * Lifecycle state of a single chat turn.
 *
 * <p>Cancellation is modelled as a {@link #FAILED} terminal state whose
 * {@code result()} future completes exceptionally with a
 * {@link java.util.concurrent.CancellationException} — mirroring the
 * {@link java.util.concurrent.CompletableFuture} contract.
 */
public enum TurnStatus {
    /** Turn is in progress: streaming tokens, executing tools, etc. */
    RUNNING,
    /** Turn finished successfully; the final assistant message is available via {@code result()}. */
    COMPLETED,
    /** Turn ended with an exception (including cancellation). */
    FAILED
}
