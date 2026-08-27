package ai.mindconnect.taskqueue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence port for task records — the ONE place whose implementation
 * decides local vs. durable vs. distributed. The atomic operations
 * ({@link #claimNext}, {@link #requestCancel}) are in the store because their
 * atomicity is storage-specific: in-memory = synchronized, Postgres =
 * {@code SELECT … FOR UPDATE SKIP LOCKED}.
 */
public interface TaskStore {

    void save(TaskRecord record);

    /**
     * Atomically inserts {@code record} unless a task with its id already
     * exists — the primitive behind caller-chosen-id idempotency. Returns the
     * EXISTING record when there is one (nothing was written), empty when the
     * insert happened. A find-then-save pair cannot replace this: two
     * concurrent submits of the same id would both pass the find and the
     * second save would overwrite a possibly RUNNING record.
     */
    Optional<TaskRecord> insertIfAbsent(TaskRecord record);

    Optional<TaskRecord> find(String id);

    List<TaskRecord> byStatus(TaskStatus status, int limit);

    /**
     * Atomically claims the next QUEUED task of one of the given types that is
     * DUE ({@link TaskRecord#runAfter()} reached): highest
     * {@link TaskRecord#priority()} first, then FIFO. The returned record is
     * already transitioned to RUNNING with {@code attempt + 1}.
     *
     * <p>The due check belongs here, inside the atomic claim — a task whose
     * time has not come must never leave QUEUED in the first place.
     */
    Optional<TaskRecord> claimNext(Set<String> types);

    /**
     * Atomic cancel transition: QUEUED → CANCELLED, RUNNING → flag set.
     * Returns the updated record, or empty when unknown/terminal.
     */
    Optional<TaskRecord> requestCancel(String id);

    /**
     * Suspends a RUNNING task on what the outcome asks for — named ids, all of
     * its children, or nothing but a notification. Resolving children here
     * keeps it atomic with the park itself.
     *
     * <p>Awaited tasks that are ALREADY terminal are dropped immediately, and
     * so is the park itself when the mailbox is not empty — in both cases the
     * returned record is QUEUED again instead of SUSPENDED. That is what makes
     * a notification from a fast child unmissable.
     */
    TaskRecord suspend(String id, TaskOutcome.Suspend suspend);

    /**
     * Appends a message to a task's mailbox, whatever its status. A message
     * always wakes: a SUSPENDED target is requeued (and returned); for any
     * other status the message only lands in the mailbox (empty).
     */
    Optional<TaskRecord> notify(String id, TaskNotification notification);

    /**
     * Hands the pending messages to the worker now running this task and
     * empties the mailbox. Anything arriving afterwards waits for the next round.
     */
    List<TaskNotification> drainNotifications(String id);

    /**
     * THE terminal transition — the one door every task leaves through:
     * persists {@code terminal} AND strikes it out of every waiting set,
     * requeueing (waking) SUSPENDED tasks with nothing left to wait for, in
     * ONE atomic step. Because persisting and waking cannot come apart, a
     * crash can no longer fall between "the child is done" and "the parent
     * knows" — the lost wake is impossible by construction, not swept up
     * after the fact.
     *
     * <p>The record still owns the transition: callers pass
     * {@code record.completed(...)}, {@code .failed(...)} or
     * {@code .cancelled()} — the store only makes it durable and audible.
     *
     * @return the tasks this termination woke, ready to claim
     * @throws LeaseLostException when this attempt no longer owns the task
     */
    List<TaskRecord> finish(TaskRecord terminal);

    /** The worker replaced its live state map (see {@link TaskContext#updateState}). */
    void updateState(String id, java.util.Map<String, Object> state);

    /** Direct children (tasks whose {@code parentTaskId} is {@code id}). */
    List<TaskRecord> byParent(String id);

    /**
     * Startup sweep: every RUNNING task (from a previous, crashed process)
     * fails with {@code reason}. Returns the swept records. A cluster store
     * scopes this to expired leases instead of all RUNNING rows.
     */
    List<TaskRecord> sweepRunning(String reason);

    /**
     * Puts a failed task back in line for another attempt, not before
     * {@code delay} from now, keeping {@code failure} on the record. The store
     * owns the clock so the delay is measured where the due check happens.
     */
    TaskRecord retry(String id, java.time.Duration delay, TaskFailure failure);

    /**
     * Retention: forgets finished task TREES whose last member ended before
     * {@code before}, and returns how many records went. Trees, not single
     * records, because a surviving child would point at a parent that no
     * longer exists — so a tree goes only when EVERY task in it is terminal.
     * One still running keeps its whole family.
     *
     * <p>The record is the answer to "what happened", so it is dropped when
     * someone asks for it — an operator clearing the board, a nightly sweep
     * passing {@code now.minus(7, DAYS)} — never on a schedule the queue
     * picked for itself.
     */
    int purgeTerminal(java.time.Instant before);

    /**
     * When the earliest not-yet-due task becomes claimable, if any — lets a
     * dispatcher sleep until then instead of polling.
     */
    java.util.Optional<java.time.Instant> nextDueAt(java.util.Set<String> types);

    // ── leases: only a store that can outlive its worker needs these ─────────

    /**
     * Says "I am still working on this" for a store that hands out leases.
     * A single-process store has no use for it: its RUNNING rows die with the
     * process that owns them, so it answers {@code true} and is done.
     *
     * @return false when the lease is gone — expired and taken over by someone
     *         else. The caller is no longer the owner of this attempt
     */
    default boolean renewLease(String id) {
        return true;
    }

    /**
     * Reclaims tasks whose owner stopped renewing: another attempt when the
     * task has attempts left, FAILED when it does not. Called periodically by
     * the queue, by every node — which is why the selection has to be atomic
     * per row rather than "all RUNNING".
     *
     * <p>Default: nothing. Without leases a store cannot tell a live worker
     * from a dead one, and guessing would kill running work.
     *
     * @return the reclaimed records
     */
    default List<TaskRecord> recoverExpired() {
        return List.of();
    }
}
