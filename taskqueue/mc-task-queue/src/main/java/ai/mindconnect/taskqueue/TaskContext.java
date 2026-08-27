package ai.mindconnect.taskqueue;

import java.util.List;
import java.util.Map;

/** What a running worker sees of its task. */
public interface TaskContext {

    TaskRecord task();

    /**
     * False on the first delivery (and on a retry before the first suspend),
     * true for every round after this task has suspended — so a fan-out worker
     * knows whether to spawn or to collect without guessing from an empty
     * state map. The queue's own answer: see {@link TaskRecord#resumed()}.
     */
    default boolean isResumed() {
        return task().resumed();
    }

    /** Re-reads the cooperative cancel flag; cheap enough to check per loop round. */
    boolean cancelRequested();

    /**
     * Registers a hook that runs the moment this task is cancelled — for work
     * that must be torn down instead of waited on: an in-flight HTTP call, an
     * open stream, a running container. Polling {@link #cancelRequested()}
     * alone can only react at the next check; this reacts at once.
     *
     * <p>Runs on the canceller's thread, so keep it short and non-throwing;
     * exceptions are swallowed. If the task is already cancelled the hook
     * runs immediately.
     */
    void onCancel(Runnable hook);

    /**
     * The task's own state map — empty on the very first execution, exactly
     * what the last {@link #updateState} wrote afterwards. Survives
     * suspend/resume and restarts.
     */
    Map<String, Object> state();

    /**
     * Replaces the task's state map — visible IMMEDIATELY via
     * {@code queue.get(id).state()}, which is the live progress view an
     * admin/task-manager UI renders ("round 3/10, waiting for web_search").
     * Same rule as payloads: data only, small, JSON-shaped — it must
     * serialize and it appears in UIs.
     */
    void updateState(Map<String, Object> state);

    /**
     * Submits a sub-task with this task as its parent — the link is set for
     * you, so nothing has to be threaded through {@link #state()} by hand.
     * Sub-tasks get a higher priority than roots by default, which is what
     * keeps an awaiting parent from starving behind fresh work.
     *
     * @return the new task's id
     */
    String submitChild(TaskSubmission submission);

    /** {@link #submitChild} for the common case of type plus payload. */
    String submitChild(String type, Map<String, Object> payload);

    /**
     * This task's children, in submission order — the live records, so their
     * status tells the parent who is still running without any bookkeeping of
     * its own.
     */
    List<TaskRecord> children();

    /**
     * The messages handed to this execution — everything that arrived since the
     * previous round, in arrival order. Delivered once: a notification sent
     * while this task is running shows up on the NEXT round, not here.
     */
    List<TaskNotification> notifications();

    /**
     * Leaves a message for another task, waking it when it is suspended and
     * asked to be woken. Sender is this task. Returns false when the target is
     * gone or already terminal.
     */
    boolean notifyTask(String taskId, Map<String, Object> payload);

    /** {@link #notifyTask} aimed at this task's parent; false when it has none. */
    boolean notifyParent(Map<String, Object> payload);
}
