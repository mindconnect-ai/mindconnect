package ai.mindconnect.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies an agent definition. See {@link EntityId}.
 */
public record AgentId(@JsonValue String value) implements EntityId {

    public AgentId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AgentId of(String value) {
        return new AgentId(value);
    }

    /** A fresh, unique id. */
    public static AgentId random() {
        return new AgentId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
