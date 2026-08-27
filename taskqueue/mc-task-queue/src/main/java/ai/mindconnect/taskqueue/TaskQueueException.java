package ai.mindconnect.taskqueue;

/** Queue-level failure: await timeout, unknown id, queue closed. */
public class TaskQueueException extends RuntimeException {

    public TaskQueueException(String message) {
        super(message);
    }
}
