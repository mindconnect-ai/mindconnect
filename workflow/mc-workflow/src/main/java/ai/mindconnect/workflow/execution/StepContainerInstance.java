package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.StepData;

/**
 * A {@link StepInstance} that owns and sequences child steps.
 *
 * @param <T> the container {@link StepData} type
 */
public interface StepContainerInstance<T extends StepData> extends StepInstance<T> {

    /** Moves execution to the named step within this container. */
    void jumpTo(String stepName);

    /** Returns the next {@link StepData} to execute, or null when finished. */
    StepData nextStep();

    /** Executes the next step and returns its instance, or null when done. */
    StepInstance<?> executeNextStep() throws HaltException, StepExecutionException;

    /** Hook called before the first child step runs. */
    void prepareExecutionOfContainer();

    /** Hook called after all child steps have run (or on halt/error). */
    void finishUpExecutionOfContainer();
}
