package ai.mindconnect.workflow.execution;

/**
 * Implemented by step instances that can redirect execution to a named step
 * after they finish (e.g. {@code IfStep}, {@code HaltStep}).
 */
public interface HasNext {

    /** Returns the name of the step to jump to, or null to continue sequentially. */
    String getNext();
}
