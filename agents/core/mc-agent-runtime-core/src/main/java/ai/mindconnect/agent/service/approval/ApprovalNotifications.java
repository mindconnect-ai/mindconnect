package ai.mindconnect.agent.service.approval;

import ai.mindconnect.taskqueue.TaskNotification;

import java.util.Map;

/**
 * The human's decision as it travels to a parked tool task — one word in the
 * notification payload. Lives HERE and not on {@link TaskNotification}
 * because the task queue is a generic module that knows nothing about
 * approvals; this class owns the vocabulary for both sides (the facade
 * sends, the gate reads).
 */
public final class ApprovalNotifications {

    private static final String KEY = "approval";
    private static final String GRANTED = "granted";
    private static final String DENIED = "denied";

    private ApprovalNotifications() {
    }

    /** "Run it" — explicitly, which is what makes Allow ONCE possible without a standing rule. */
    public static TaskNotification approvalGranted() {
        return TaskNotification.from(null, Map.of(KEY, GRANTED));
    }

    /** "Do not run it" — the gate turns this into a failed, model-readable TOOL_RESULT. */
    public static TaskNotification approvalDenied() {
        return TaskNotification.from(null, Map.of(KEY, DENIED));
    }

    /** The decision in {@code notification}: TRUE granted, FALSE denied, null when it carries none. */
    public static Boolean decision(TaskNotification notification) {
        Object value = notification.payload().get(KEY);
        if (GRANTED.equals(value)) return Boolean.TRUE;
        if (DENIED.equals(value)) return Boolean.FALSE;
        return null;
    }
}
