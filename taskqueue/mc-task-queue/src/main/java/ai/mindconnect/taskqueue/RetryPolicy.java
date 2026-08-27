package ai.mindconnect.taskqueue;

import java.time.Duration;
import java.util.Optional;

/**
 * Decides what happens after a failed attempt: give up, or try again after a
 * delay. The queue owns the mechanism — requeueing with a {@code runAfter} —
 * this only chooses.
 */
@FunctionalInterface
public interface RetryPolicy {

    /**
     * @return how long to wait before the next attempt, or empty to fail the
     *         task for good. Called with the record as it was when the attempt
     *         failed, so {@code task.attempt()} is the attempt that just failed.
     */
    Optional<Duration> retryIn(TaskRecord task, TaskFailure failure);

    /** Fail on the first error — the default, and what the queue did before. */
    static RetryPolicy none() {
        return (task, failure) -> Optional.empty();
    }

    /**
     * Doubles the delay per attempt up to {@code cap}, for as many attempts as
     * the task itself allows ({@link TaskRecord#maxAttempts()}). Per-task,
     * because how often a job is worth retrying is a property of the job.
     */
    static RetryPolicy exponentialBackoff(Duration base, Duration cap) {
        return (task, failure) -> {
            if (task.attempt() >= task.maxAttempts()) return Optional.empty();
            long millis = base.toMillis() << Math.min(task.attempt() - 1, 20);
            return Optional.of(Duration.ofMillis(Math.min(millis, cap.toMillis())));
        };
    }
}
