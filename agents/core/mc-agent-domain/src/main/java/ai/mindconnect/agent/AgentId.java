package ai.mindconnect.agent;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies an agent definition. See {@link EntityId}.
 */
public record AgentId(@JsonValue String value) implements EntityId {

    public AgentId {
        EntityId.check(value);
    }

    public static AgentId of(String value) {
        return new AgentId(value);
    }

    /**
     * In JSON an id is its plain value. Agents stored while the runtime was
     * rebuilt carry it as {@code {"value": "…"}}, which is still read.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static AgentId fromJson(Object json) {
        return new AgentId(json instanceof Map<?, ?> wrapped ? String.valueOf(wrapped.get("value"))
                : String.valueOf(json));
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
