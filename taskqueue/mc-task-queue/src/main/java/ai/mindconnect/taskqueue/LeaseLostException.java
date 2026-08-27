package ai.mindconnect.taskqueue;

/**
 * This attempt no longer owns its task: the lease expired and another node
 * took over. Thrown by a leasing store when a fenced-out worker tries to
 * write a transition — the ONLY correct reaction is to stop quietly: no
 * terminal event, no wake, no retry. The attempt that owns the task now is
 * responsible for all of that.
 */
public class LeaseLostException extends TaskQueueException {

    public LeaseLostException(String message) {
        super(message);
    }
}
