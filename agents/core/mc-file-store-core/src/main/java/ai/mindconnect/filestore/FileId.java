package ai.mindconnect.filestore;

import ai.mindconnect.agent.EntityId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies a stored file. See {@link EntityId}.
 */
public record FileId(@JsonValue String value) implements EntityId {

    public FileId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static FileId of(String value) {
        return new FileId(value);
    }

    /** A fresh, unique id. */
    public static FileId random() {
        return new FileId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
