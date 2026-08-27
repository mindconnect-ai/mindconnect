package ai.mindconnect.taskqueue;

import java.util.Set;

/**
 * What a worker execution produced.
 *
 * <p>{@link Suspend} is the continuation mechanic (concept 11, variant b):
 * the worker returns instead of blocking — its task suspends, occupying no
 * thread and no worker slot, and is re-queued (woken) when every awaited
 * task is terminal. The next execution starts fresh ({@code attempt + 1})
 * and finds its own {@link TaskContext#state()} where it left it. Because
 * nobody blocks, bounded worker pools cannot deadlock on parent/child
 * chains — and a suspended task survives restarts and can resume on
 * another node.
 */
public sealed interface TaskOutcome {

    record Completed(String result) implements TaskOutcome { }

    /**
     * Park until the awaited tasks are all terminal (COMPLETED, FAILED or
     * CANCELLED) — or until a {@link TaskNotification} arrives: a message
     * always wakes, so a parent reacts to each child, not only to the last.
     * Save your continuation cursor via {@link TaskContext#updateState}
     * BEFORE returning this.
     */
    record Suspend(Set<String> awaitedTaskIds, boolean awaitChildren) implements TaskOutcome { }

    static TaskOutcome done(String result) {
        return new Completed(result);
    }

    static Suspend suspendUntil(String... taskIds) {
        return new Suspend(Set.of(taskIds), false);
    }

    static Suspend suspendUntil(Set<String> taskIds) {
        return new Suspend(Set.copyOf(taskIds), false);
    }

    /**
     * Park until every sub-task of this task is terminal — the usual fan-out,
     * without naming a single id. The queue resolves the children at the
     * moment of suspending, so one submitted a heartbeat earlier still counts,
     * and a task with no children at all is simply requeued.
     */
    static Suspend suspendUntilChildren() {
        return new Suspend(Set.of(), true);
    }

    /**
     * Park until someone sends a {@link TaskNotification} — no awaited set at
     * all. For tasks woken by the outside world (a webhook, an operator) or by
     * a sibling rather than by a child finishing.
     */
    static Suspend suspendUntilNotified() {
        return new Suspend(Set.of(), false);
    }
}
