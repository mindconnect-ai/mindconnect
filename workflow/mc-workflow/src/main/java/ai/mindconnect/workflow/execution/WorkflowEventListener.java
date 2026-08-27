package ai.mindconnect.workflow.execution;

/**
 * Observer for workflow execution lifecycle events.
 * All methods have empty default implementations so listeners only need to
 * override what they care about.
 */
public interface WorkflowEventListener {

    default void beforeStepExecute(StepInstance<?> stepInstance) {}

    default void afterStepExecute(StepInstance<?> stepInstance) {}

    default void onStepExecuteError(StepInstance<?> stepInstance, Exception e) {}
}
