package ai.mindconnect.agent.runtime.domain;

import ai.mindconnect.agent.EntityId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies one recorded LLM call. See {@link EntityId}.
 */
public record TraceId(@JsonValue String value) implements EntityId {

    public TraceId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static TraceId of(String value) {
        return new TraceId(value);
    }

    /** A fresh, unique id. */
    public static TraceId random() {
        return new TraceId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
