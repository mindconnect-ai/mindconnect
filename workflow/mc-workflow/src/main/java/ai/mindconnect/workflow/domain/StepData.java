package ai.mindconnect.workflow.domain;

/**
 * Root marker interface for all step configuration objects.
 * Intentionally free of serialization annotations — wire format is the
 * responsibility of an optional serialization module (e.g. mc-workflow-jackson).
 */
public interface StepData {

    /** Short type identifier, e.g. "foreach", "if", "httpcall". */
    String getType();

    /** Logical name / id of this step — used for jumpTo and result assignment. */
    String getName();

    void setName(String name);

    /**
     * When non-null, the step result is assigned to a variable with this name
     * in the enclosing scope after successful execution.
     */
    String getAssignResultToVar();

    void setAssignResultToVar(String assignResultToVar);
}
