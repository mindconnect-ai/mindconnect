package ai.mindconnect.agent.protocol;

/**
 * Lifecycle of a {@link Response}.
 *
 * <pre>
 * QUEUED ──► IN_PROGRESS ──► COMPLETED
 *                            INCOMPLETE   (needs more input — see {@link IncompleteReason})
 *                            FAILED
 *                            CANCELLED
 * </pre>
 *
 * All states right of the arrow are terminal. There is no SUSPENDED here:
 * a response that waits for a human <em>ends</em> as {@code INCOMPLETE} with
 * an {@code ApprovalRequest} item, and the answer starts a new response on
 * the same conversation (concept 7). Suspension of live work (e.g.
 * {@code docker pause}) is an internal run-tree state, not a protocol state.
 */
public enum ResponseStatus {
    QUEUED,
    IN_PROGRESS,
    COMPLETED,
    INCOMPLETE,
    FAILED,
    CANCELLED;

    public boolean terminal() { return this != QUEUED && this != IN_PROGRESS; }
}
