package ai.mindconnect.taskqueue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * What a producer hands to {@link TaskQueue#submit}.
 *
 * <p><b>Payload is data only</b> — ids and values, JSON-shaped. Never object
 * references, lambdas or services: the worker resolves everything through its
 * own repositories. This rule is what keeps the cluster path open (a payload
 * that cannot be serialized cannot travel to another node).
 *
 * @param priority     higher runs first (tie: FIFO). Convention from the
 *                     agent runtime: {@code priority = depth}, so child tasks
 *                     overtake root tasks and parents never starve waiting.
 * @param parentTaskId spawning task, {@code null} for roots — lineage for the
 *                     task manager, not a control-flow link
 * @param runAfter     not claimable before this instant; {@code null} means as
 *                     soon as a worker is free. One field for both a delay and
 *                     a fixed point in time
 * @param maxAttempts  how often this task may be claimed in total; 1 (the
 *                     default) means no retry
 */
public record TaskSubmission(
        String type,
        Map<String, Object> payload,
        int priority,
        String parentTaskId,
        Instant runAfter,
        int maxAttempts,
        String id
) {

    public static TaskSubmission of(String type, Map<String, Object> payload) {
        return new TaskSubmission(type, payload, 0, null, null, 1, null);
    }

    public TaskSubmission withPriority(int priority) {
        return new TaskSubmission(type, payload, priority, parentTaskId, runAfter, maxAttempts, id);
    }

    public TaskSubmission withParent(String parentTaskId) {
        return new TaskSubmission(type, payload, priority, parentTaskId, runAfter, maxAttempts, id);
    }

    /**
     * A caller-chosen task id ({@code null} lets the queue generate one).
     * Choose it deterministically from the DOMAIN identity of the work
     * ("task_turn_<turnId>") and submitting becomes idempotent: a second
     * submit of the same id returns the existing task instead of creating a
     * twin — which is exactly what a resume that cannot remember whether it
     * already submitted needs. It also makes the task findable from domain
     * state alone, without any caller-side registry.
     */
    public TaskSubmission withId(String id) {
        return new TaskSubmission(type, payload, priority, parentTaskId, runAfter, maxAttempts, id);
    }

    /** Run no earlier than {@code delay} from now. */
    public TaskSubmission after(Duration delay) {
        return at(Instant.now().plus(delay));
    }

    /** Run no earlier than {@code instant}. */
    public TaskSubmission at(Instant instant) {
        return new TaskSubmission(type, payload, priority, parentTaskId, instant, maxAttempts, id);
    }

    /**
     * Allow up to {@code maxAttempts} claims before the task fails for good.
     * How often a job is worth retrying is a property of the job, so it rides
     * on the submission rather than on a queue-wide setting.
     */
    public TaskSubmission withMaxAttempts(int maxAttempts) {
        return new TaskSubmission(type, payload, priority, parentTaskId, runAfter, maxAttempts, id);
    }
}
