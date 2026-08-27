package ai.mindconnect.taskqueue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The queue port: submit data-only tasks, observe them by id, cancel them
 * cooperatively. Implementations: {@code LocalTaskQueue} (virtual threads,
 * pluggable {@link TaskStore}); a cluster implementation shares the SAME
 * interface and store schema (DB claim via SKIP LOCKED + lease) — callers
 * never notice the difference.
 *
 * <p>Sub-agent pattern (concept 11): a worker that needs work done by another
 * task SUBMITS it (with {@code priority = depth}, so children overtake roots)
 * and either {@link #await}s the id (variant a — safe because workers are
 * virtual threads, never a bounded pool) or ends its own task and lets the
 * child's completion re-enqueue the parent (variant b). No handles, no
 * references — the id is the only coupling.
 */
public interface TaskQueue {

    /** Persists the task as QUEUED and returns its id. */
    String submit(TaskSubmission submission);

    Optional<TaskRecord> get(String taskId);

    /**
     * The tasks submitted with this one as their parent, in submission order.
     * The link lives on the record ({@link TaskRecord#parentTaskId()}), so the
     * tree is queryable without anyone bookkeeping ids by hand — and it is the
     * same link {@link #cancel} cascades along.
     */
    List<TaskRecord> children(String taskId);

    /**
     * The tasks currently in {@code status}, in store order. The queue IS the
     * registry of running work — callers look their task up here instead of
     * keeping maps of their own (a task manager does the same).
     */
    List<TaskRecord> byStatus(TaskStatus status, int limit);

    /**
     * Cancels cooperatively: a QUEUED task flips to CANCELLED immediately, a
     * RUNNING task gets the flag and flips when its worker exits.
     *
     * @return {@code false} if the task was unknown or already terminal
     */
    boolean cancel(String taskId);

    /**
     * Leaves a message for a task from outside the queue — a webhook, an
     * operator, a scheduler. Wakes it when it is suspended and asked to be
     * woken; otherwise the message waits in its mailbox. False when the task is
     * unknown or already terminal.
     */
    boolean notify(String taskId, TaskNotification notification);

    /**
     * Blocks (cheaply — park a virtual thread) until the task is terminal.
     *
     * @throws TaskQueueException on timeout or unknown id
     */
    TaskRecord await(String taskId, Duration timeout);

    /** Registers the worker for a task type; tasks of unregistered types stay QUEUED. */
    void register(String taskType, TaskWorker worker);

    boolean hasRegisteredType(String type);
}
