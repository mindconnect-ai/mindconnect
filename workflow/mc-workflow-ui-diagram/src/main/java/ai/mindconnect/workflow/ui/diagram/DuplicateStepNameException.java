package ai.mindconnect.workflow.ui.diagram;

/**
 * Thrown by {@link WorkflowDiagramBuilder} when two {@code StepData}s in the
 * same workflow share a {@code name} value. The builder relies on step names
 * being unique so that {@code UiDiagramNode.stepRef} can stably reference the
 * underlying step for editor mutations. Auto-generated names ({@code step_1},
 * {@code step_2}, ...) never collide; this exception only fires for
 * user-supplied duplicates.
 */
public class DuplicateStepNameException extends RuntimeException {

    public DuplicateStepNameException(String name) {
        super("Duplicate step name '" + name + "' — step names must be unique within a workflow "
            + "so the diagram can use them as stable references.");
    }
}
