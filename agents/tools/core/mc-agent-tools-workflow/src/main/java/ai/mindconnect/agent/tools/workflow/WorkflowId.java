package ai.mindconnect.agent.tools.workflow;

import ai.mindconnect.agent.EntityId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies a persisted workflow definition; the value is the workflow's name. See {@link EntityId}.
 */
public record WorkflowId(@JsonValue String value) implements EntityId {

    public WorkflowId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static WorkflowId of(String value) {
        return new WorkflowId(value);
    }

    /** A fresh, unique id. */
    public static WorkflowId random() {
        return new WorkflowId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
