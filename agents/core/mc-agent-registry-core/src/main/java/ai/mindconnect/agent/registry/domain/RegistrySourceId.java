package ai.mindconnect.agent.registry.domain;

import ai.mindconnect.agent.EntityId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Which registry — the same shape as every other id: a value and nothing
 * else. The namespace sits with the store that holds the source; one process
 * serves one.
 */
public record RegistrySourceId(@JsonValue String value) implements EntityId {

    public RegistrySourceId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static RegistrySourceId of(String value) {
        return new RegistrySourceId(value);
    }

    public static RegistrySourceId random() {
        return new RegistrySourceId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
