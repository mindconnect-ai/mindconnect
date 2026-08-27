package ai.mindconnect.workflow.execution;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Thrown as a control-flow signal to suspend the workflow.
 * This is NOT an error — the workflow can be resumed via
 * {@link WorkflowExecutorService#continueWorkflow}.
 */
@Data
@Builder
@EqualsAndHashCode(callSuper = false)
public class HaltException extends Exception {

    private StepInstance<?> stepInstance;

    @Builder.Default
    private boolean returnResult = true;

    /** The step name to resume from when the workflow is continued. */
    private String next;
}
