package ai.mindconnect.workflow.execution;

import lombok.Getter;

/**
 * Immutable result of a workflow execution.
 * Replaces silent exception swallowing — callers always know what happened.
 */
@Getter
public final class WorkflowResult {

    private final WorkflowInstance instance;
    private final ExecutionState state;
    private final Throwable error;

    private WorkflowResult(WorkflowInstance instance, ExecutionState state, Throwable error) {
        this.instance = instance;
        this.state = state;
        this.error = error;
    }

    public static WorkflowResult success(WorkflowInstance instance) {
        return new WorkflowResult(instance, ExecutionState.FINISHED, null);
    }

    public static WorkflowResult halted(WorkflowInstance instance, HaltException halt) {
        return new WorkflowResult(instance, ExecutionState.HALTED, halt);
    }

    public static WorkflowResult error(WorkflowInstance instance, Throwable error) {
        return new WorkflowResult(instance, ExecutionState.ERROR, error);
    }

    public boolean isSuccess()  { return state == ExecutionState.FINISHED; }
    public boolean isHalted()   { return state == ExecutionState.HALTED; }
    public boolean isError()    { return state == ExecutionState.ERROR; }

    /**
     * The line to show for a run that did not finish, null for one that did:
     * {@code "halted"} for a halt, and for a failure the cause a step failure
     * wraps — the one the user needs — as its message, or its type when it has none.
     */
    public String failureMessage() {
        if (isSuccess()) return null;
        if (isHalted()) return "halted";
        if (error == null) return "Execution failed.";
        Throwable root = error.getCause() != null ? error.getCause() : error;
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    /** Shortcut for the workflow's final result value. */
    public Object getResult() {
        return instance.getResult();
    }

    @Override
    public String toString() {
        return "WorkflowResult{state=" + state + ", result=" + getResult()
                + (error != null ? ", error=" + error.getMessage() : "") + "}";
    }
}
