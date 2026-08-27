package ai.mindconnect.taskqueue;

import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Puts the task's identity into the logging context around every execution,
 * so lines from deep inside a worker carry the correlation without anyone
 * passing ids around.
 *
 * <p>An advisor rather than a listener on purpose: this must WRAP the
 * execution (set before, restore after), which is what the policy plane is
 * for. It matters more here than in a thread-pool world — every task runs on
 * its own virtual thread, so nothing is inherited.
 *
 * <pre>
 * queue.addAdvisor(MdcTaskAdvisor.withPayloadKeys("responseId", "sessionId"));
 * </pre>
 */
public final class MdcTaskAdvisor implements TaskAdvisor {

    public static final String TASK_ID = "taskId";
    public static final String TASK_TYPE = "taskType";

    private final List<String> payloadKeys;

    private MdcTaskAdvisor(List<String> payloadKeys) {
        this.payloadKeys = List.copyOf(payloadKeys);
    }

    /** Task id and type only. */
    public static MdcTaskAdvisor standard() {
        return new MdcTaskAdvisor(List.of());
    }

    /** Task id and type plus the named payload entries (e.g. responseId, sessionId). */
    public static MdcTaskAdvisor withPayloadKeys(String... payloadKeys) {
        return new MdcTaskAdvisor(List.of(payloadKeys));
    }

    @Override
    public int order() {
        return Integer.MIN_VALUE + 1;          // outermost: everything else logs inside it
    }

    @Override
    public TaskOutcome aroundExecute(TaskContext ctx, Execution chain) throws Exception {
        TaskRecord task = ctx.task();
        Map<String, Object> payload = task.payload();
        List<String> applied = new ArrayList<>(payloadKeys.size() + 2);

        put(applied, TASK_ID, task.id());
        put(applied, TASK_TYPE, task.type());
        for (String key : payloadKeys) {
            Object value = payload.get(key);
            if (value != null) put(applied, key, value.toString());
        }
        try {
            return chain.proceed(ctx);
        } finally {
            applied.forEach(MDC::remove);      // leave the thread as we found it
        }
    }

    private static void put(List<String> applied, String key, String value) {
        MDC.put(key, value);
        applied.add(key);
    }
}
