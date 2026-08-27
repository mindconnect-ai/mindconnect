package ai.mindconnect.taskqueue;

import java.time.Instant;
import java.util.Map;

/**
 * A message one task leaves for another — the imperative counterpart to the
 * declarative {@link TaskOutcome.Suspend} join.
 *
 * <p>A notification is a <em>durable fact, not an event in flight</em>. It is
 * appended to the target's mailbox whatever the target is doing: a SUSPENDED
 * task is woken, a RUNNING one keeps running and finds the message on its next
 * round, and a task that has not suspended yet cannot miss it. That is what
 * closes the lost-wakeup race a fast child would otherwise win against its
 * parent.
 *
 * <p>Data-only and small, like {@link TaskRecord#payload()}: a pointer or a
 * short summary, not the child's actual output.
 *
 * @param fromTaskId who sent it — {@code null} when it came from outside the
 *                   queue (a webhook, an operator)
 * @param payload    the message body; empty when the wakeup itself is the point
 */
public record TaskNotification(
        String fromTaskId,
        Map<String, Object> payload,
        Instant at
) {

    public TaskNotification {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        at = at == null ? Instant.now() : at;
    }

    public static TaskNotification from(String fromTaskId, Map<String, Object> payload) {
        return new TaskNotification(fromTaskId, payload, Instant.now());
    }

    /** A bare wakeup with no body — "look at me, something changed". */
    public static TaskNotification from(String fromTaskId) {
        return new TaskNotification(fromTaskId, Map.of(), Instant.now());
    }
}
