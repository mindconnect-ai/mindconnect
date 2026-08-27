package ai.mindconnect.taskqueue.schedule;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for schedules — the same shape as {@code TaskStore}, and
 * for the same reason: one method decides whether this works on one node or
 * on twenty.
 *
 * <p>That method is {@link #claimFiring}. Every node ticks, every node
 * computes the same due firing, and exactly one of them may submit a task for
 * it. Note what it is NOT: a lock. A lock has to be held, renewed and
 * released, and a node that dies holding one blocks the schedule until it
 * expires. A compare-and-set on "which firing have we already dealt with"
 * has none of those states — it either wins or it does not, and a node that
 * dies right after winning loses one firing instead of blocking all of them.
 */
public interface ScheduleStore {

    void save(TaskSchedule schedule);

    Optional<TaskSchedule> find(String id);

    /** Every schedule, enabled or not — the scheduler filters, the store does not. */
    List<TaskSchedule> all();

    boolean delete(String id);

    /**
     * Compare-and-set on {@link TaskSchedule#lastFiredFor()}: succeeds only if
     * this schedule has not been claimed for {@code firedFor} or anything later.
     *
     * @return the updated schedule for the winner, empty for everyone else —
     *         including a second attempt by the same node, which is what makes
     *         a retried tick harmless
     */
    Optional<TaskSchedule> claimFiring(String id, Instant firedFor);

    /**
     * Records which task the firing produced. Bookkeeping, deliberately
     * separate from {@link #claimFiring}: it must not be able to disturb the
     * compare-and-set field.
     */
    void recordFired(String id, String taskId);
}
