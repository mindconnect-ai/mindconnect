package ai.mindconnect.agent.runtime.domain;

/**
 * Lifecycle state of a single chat turn, as one handle sees it.
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
    /**
     * The turn waits for a human: a tool call is parked at the approval gate. The turn itself
     * is still alive — answering the open question continues it (see {@code TurnResult}).
     */
    INCOMPLETE,
    /** Turn ended with an exception (including cancellation). */
    FAILED
}
