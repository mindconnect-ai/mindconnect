package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.StepData;

import java.util.List;

/**
 * Executable unit corresponding to one {@link StepData} configuration object.
 *
 * @param <T> the concrete {@link StepData} type this instance is configured by
 */
public interface StepInstance<T extends StepData> {

    /**
     * Binds this instance to its configuration, parent container, and shared context.
     * Called once by the engine before {@link #execute()}.
     */
    void init(T stepData, StepContainerInstance<?> parent, WorkflowContext context);

    WorkflowContext getWorkflowContext();

    void setWorkflowContext(WorkflowContext context);

    T getConfig();

    VariableScope getVariableScope();

    /**
     * Performs the step's work. Implementations may throw:
     * <ul>
     *   <li>{@link HaltException} — intentional workflow suspension</li>
     *   <li>any other Exception — treated as an execution error</li>
     * </ul>
     */
    void execute() throws Exception;

    Object getResult();

    StepExecutionInfo getStepExecutionInfo();

    void setStepExecutionInfo(StepExecutionInfo info);

    void setState(ExecutionState state);

    ExecutionState getState();

    /**
     * The instances this step actually ran, in execution order — the record of
     * what happened <em>inside</em> it.
     *
     * <p>For a container it is simply its executed children. It exists on
     * {@link StepInstance} rather than on {@link StepContainerInstance} because
     * a step can run sub-steps without being a container: an {@link IfStep} runs
     * the branch it selected, and a caller inspecting a finished run has no
     * other way to learn which branch that was.
     *
     * <p>Empty for a plain leaf step.
     */
    List<StepInstance<?>> getChildInstances();
}
