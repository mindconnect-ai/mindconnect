package ai.mindconnect.taskqueue;

/**
 * The code behind a task type. Registered per type, called by the queue —
 * once per round, so a task that suspends comes back through here.
 *
 * <p>Deliberately a single method: the alternative, a second {@code onResumed}
 * entry point defaulting to this one, cannot be expressed as a lambda, and a
 * fan-out worker registered as a lambda would then silently spawn its children
 * again on every resume. {@link TaskContext#isResumed()} makes the distinction
 * explicit where it is visible instead — and it is the queue's own answer, not
 * a guess from an empty state map.
 *
 * <pre>{@code
 * queue.register("parent", ctx -> {
 *     if (!ctx.isResumed()) {                       // first delivery
 *         ctx.submitChild("child", Map.of());
 *         return TaskOutcome.suspendUntilChildren();
 *     }
 *     return TaskOutcome.done(collect(ctx));        // continuation
 * });
 * }</pre>
 */
@FunctionalInterface
public interface TaskWorker {

    /**
     * Runs one round of this task. The first delivery starts from the payload;
     * a continuation ({@link TaskContext#isResumed()}) picks up what the
     * previous round left in {@code ctx.task().state()}, this round's messages
     * in {@code ctx.notifications()} and what is still outstanding in
     * {@code ctx.task().waitingFor()}.
     */
    TaskOutcome execute(TaskContext ctx) throws Exception;
}
