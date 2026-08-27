package ai.mindconnect.workflow.domain;

import ai.mindconnect.schema.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Suspends the workflow until someone continues it.
 *
 * <p>A halt is how a workflow waits for the outside world — an approval, a
 * delivery, a human reading a screen. Execution stops here, the instance can be
 * written out, and {@code WorkflowExecutorService#continueWorkflow(instance,
 * params)} picks it up again with whatever the world had to say: the {@code
 * params} are assigned into the workflow's root scope before the next step runs,
 * so the steps after the halt read them like any other variable.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class HaltData extends BaseStepData {

    /** Optional boolean expression — only halts when this evaluates to true. */
    private String condition;

    /** Whether to propagate the resolved result upward on halt. */
    private boolean returnResult = true;

    /** Expression whose value becomes the workflow result on halt. */
    private String returnResultExpression;

    /** Step name to resume from on continuation. Null continues at the next step. */
    private String next;

    /**
     * The inputs this halt is waiting for, as an object {@link Schema} — each
     * property is a typed field, so a resume renders a real form. An agent loop
     * halts for a reply and declares a multiline {@code userMessage}; an approval
     * declares an enum {@code verdict} and a string {@code approver}.
     *
     * <p>Purely declarative: the engine assigns whatever params a resume hands
     * it, named here or not. What this buys is that a caller — a UI, an API, the
     * next process to pick the instance up — can ask "what does this suspension
     * need, and in what shape?" and, for the agent case, hand the same schema to
     * an LLM as a tool signature via {@link Schema#toMap()}.
     */
    private Schema resumeParams = Schema.object();
}
