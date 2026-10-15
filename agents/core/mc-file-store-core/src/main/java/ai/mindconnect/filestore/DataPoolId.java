package ai.mindconnect.filestore;

import ai.mindconnect.agent.EntityId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies a data pool — a curated collection of files. See {@link EntityId}.
 */
public record DataPoolId(@JsonValue String value) implements EntityId {

    public DataPoolId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static DataPoolId of(String value) {
        return new DataPoolId(value);
    }

    /** A fresh, unique id. */
    public static DataPoolId random() {
        return new DataPoolId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
