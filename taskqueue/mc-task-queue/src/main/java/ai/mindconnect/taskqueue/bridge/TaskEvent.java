package ai.mindconnect.taskqueue.bridge;

import ai.mindconnect.taskqueue.TaskRecord;

/**
 * One thing that happened to a task, with the record as it looked right then
 * — the unit a live task view renders.
 *
 * @param type what happened; {@link Type#STATE} is progress within a run,
 *             everything else is a lifecycle transition
 */
public record TaskEvent(Type type, TaskRecord task) {

    public enum Type { SUBMITTED, STARTED, STATE, SUSPENDED, WOKEN, TERMINAL }
}
