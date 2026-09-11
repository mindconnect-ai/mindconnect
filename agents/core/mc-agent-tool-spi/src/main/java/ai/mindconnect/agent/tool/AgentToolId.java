package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.EntityId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies one tool binding of an agent.
 *
 * <p>A binding belongs to exactly one agent and is never looked up outside
 * it, so the value only has to be unique within that agent. The agent an id
 * belongs to is known from the {@code AgentDefinition} that lists it, and, at
 * call time, from the {@link ToolCallScope}. See {@link EntityId}.
 */
public record AgentToolId(@JsonValue String value) implements EntityId {

    public AgentToolId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AgentToolId of(String value) {
        return new AgentToolId(value);
    }

    /** A fresh, unique id. */
    public static AgentToolId random() {
        return new AgentToolId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
