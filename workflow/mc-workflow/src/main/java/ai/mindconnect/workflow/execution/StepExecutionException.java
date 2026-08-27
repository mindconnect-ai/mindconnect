package ai.mindconnect.workflow.execution;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Wraps an unexpected exception thrown during step execution. */
@Data
@Builder
@EqualsAndHashCode(callSuper = false)
public class StepExecutionException extends Exception {

    private StepInstance<?> stepInstance;
    private Exception causingException;

    @Override
    public String getMessage() {
        String step = stepInstance != null && stepInstance.getConfig() != null
                ? stepInstance.getConfig().getName() : "unknown";
        String cause = causingException != null ? causingException.getMessage() : "unknown";
        return "Step '" + step + "' failed: " + cause;
    }
}
