package ai.mindconnect.taskqueue;

/**
 * Lifecycle of a task.
 *
 * <pre>
 * QUEUED ──► RUNNING ──► COMPLETED
 *    ▲          │        FAILED
 *    │          ▼
 *    └──────  SUSPENDED         (waiting on other tasks — no thread, no slot)
 *    (all awaited terminal)
 * QUEUED / RUNNING / SUSPENDED ──► CANCELLED
 * </pre>
 *
 * A cancel on a QUEUED task cancels immediately; a cancel on a RUNNING task
 * sets {@link TaskRecord#cancelRequested()} and the worker exits
 * cooperatively — the status flips to CANCELLED when it does.
 */
public enum TaskStatus {
    QUEUED,
    RUNNING,
    /** Suspended until every task in {@link TaskRecord#waitingFor()} is terminal — no thread, no slot. */
    SUSPENDED,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
