package ai.mindconnect.taskqueue.demo.worker;

import ai.mindconnect.taskqueue.TaskContext;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskWorker;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Counts down n steps with a delay per step, writing each step into the task
 * state — the cheapest possible STATE-event stream. With {@code failOnStep}
 * set, the first attempt fails there, so a resubmit with maxAttempts &gt; 1
 * shows the FAILED → retry → COMPLETED arc including the recorded failure.
 */
@Component
public class CountdownWorker implements TaskWorker {

    public static final String TYPE = "countdown";

    @Override
    public TaskOutcome execute(TaskContext ctx) throws Exception {
        Map<String, Object> payload = ctx.task().payload();
        int steps = ((Number) payload.get("steps")).intValue();
        long delayMs = ((Number) payload.getOrDefault("delayMs", 500)).longValue();
        int failOnStep = ((Number) payload.getOrDefault("failOnStep", -1)).intValue();

        // The state is the continuation cursor: it survives the attempt, so a
        // retry — planned failure or a node that died mid-count — carries on
        // where the last report left off instead of starting over. The
        // reported step may run twice (at-least-once); for a countdown that
        // is harmless, which is exactly the idempotency the contract asks of
        // workers.
        Object reported = ctx.state().get("step");
        int alreadyCounted = reported instanceof Number n ? n.intValue() : 0;

        for (int step = alreadyCounted + 1; step <= steps; step++) {
            if (ctx.cancelRequested()) {
                return TaskOutcome.done("cancelled at step " + step);
            }
            if (step == failOnStep && ctx.task().attempt() == 1) {
                throw new IllegalStateException("planned failure at step " + step);
            }
            Thread.sleep(delayMs);
            ctx.updateState(Map.of("step", step, "remaining", steps - step));
        }
        return TaskOutcome.done("counted " + steps + " steps"
                + (alreadyCounted > 0 ? " (resumed at " + (alreadyCounted + 1) + ")" : ""));
    }
}
