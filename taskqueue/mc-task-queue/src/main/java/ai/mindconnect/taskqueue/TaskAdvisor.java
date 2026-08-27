package ai.mindconnect.taskqueue;

/**
 * Policy hook: intervenes in queue behavior, synchronously and in-band —
 * the queue's sibling of the runtime's {@code ToolAdvisor}. Two seams:
 *
 * <ul>
 *   <li>{@link #beforeSubmit} — validate, enrich or reject a submission
 *       (depth limits, quotas, priority policy). Throwing rejects the
 *       submit; the caller sees the exception.</li>
 *   <li>{@link #aroundExecute} — wraps every worker execution as a chain:
 *       timing, MDC, retries (call {@code chain.proceed} again), resource
 *       semaphores (the K11 LLM-call limit lives here), or short-circuiting
 *       without running the worker at all.</li>
 * </ul>
 *
 * Advisors run in {@link #order()} (lower = outermost). Unlike listeners,
 * an advisor's exception is REAL: it fails the submit or the execution.
 */
public interface TaskAdvisor {

    default TaskSubmission beforeSubmit(TaskSubmission submission) {
        return submission;
    }

    default TaskOutcome aroundExecute(TaskContext ctx, Execution chain) throws Exception {
        return chain.proceed(ctx);
    }

    /** Lower runs further out — sees the raw context and the final outcome. */
    default int order() {
        return 0;
    }

    /** Continuation: the next advisor down, or the worker at the tail. */
    interface Execution {
        TaskOutcome proceed(TaskContext ctx) throws Exception;
    }
}
