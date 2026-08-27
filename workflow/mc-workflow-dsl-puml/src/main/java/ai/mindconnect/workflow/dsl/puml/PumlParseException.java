package ai.mindconnect.workflow.dsl.puml;

import ai.mindconnect.workflow.domain.WorkflowData;

/**
 * Thrown when the PlantUML DSL input cannot be parsed into a valid
 * {@link WorkflowData}.
 */
public class PumlParseException extends RuntimeException {

    public PumlParseException(String message) {
        super(message);
    }

    public PumlParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
