package ai.mindconnect.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies a chat session. See {@link EntityId}.
 */
public record SessionId(@JsonValue String value) implements EntityId {

    public SessionId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static SessionId of(String value) {
        return new SessionId(value);
    }

    /** A fresh, unique id. */
    public static SessionId random() {
        return new SessionId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
