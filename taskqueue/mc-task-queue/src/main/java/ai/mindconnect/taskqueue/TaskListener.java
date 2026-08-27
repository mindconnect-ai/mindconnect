package ai.mindconnect.taskqueue;

/**
 * Observation hook: notified of queue lifecycle events, AFTER the fact.
 * All methods default to no-ops — implement what you care about (the
 * {@code WorkflowEventListener} pattern).
 *
 * <p>Observation-plane rules (concept 1/3): a listener may not change
 * anything and can not break anything — exceptions are swallowed by the
 * queue. Callbacks run synchronously on the transition's thread, so keep
 * them fast; bridge to a {@code Channel} or hand off to your own executor
 * for heavy work. To INTERVENE in behavior, use a {@link TaskAdvisor}.
 */
public interface TaskListener {

    default void onSubmitted(TaskRecord task) { }

    /** A worker claimed the task (every attempt — resumes included). */
    default void onStarted(TaskRecord task) { }

    default void onSuspended(TaskRecord task) { }

    /** All awaited tasks turned terminal; the task is QUEUED again. */
    /**
     * The task is back in line: everything it awaited turned terminal, a
     * notification arrived — or it asked to suspend and was requeued at once
     * because there was nothing left to wait for. The last case never reached
     * SUSPENDED, so {@link #onSuspended} is not a reliable "is it waiting" signal.
     */
    default void onWoken(TaskRecord task) { }

    /**
     * The worker replaced its state map ({@link TaskContext#updateState}) —
     * progress, not lifecycle: "round 3, calling web_search". Fires a handful
     * of times per task, which is what makes a live view useful; it is not a
     * data channel, so keep what you put in the state small.
     */
    default void onStateChanged(TaskRecord task) { }

    /** COMPLETED, FAILED or CANCELLED — read {@code task.status()}. */
    default void onTerminal(TaskRecord task) { }
}
