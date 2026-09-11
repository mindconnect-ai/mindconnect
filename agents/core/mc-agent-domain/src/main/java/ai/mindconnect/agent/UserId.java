package ai.mindconnect.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies a user. Not an {@link EntityId}: the value is whatever the
 * identity provider hands over as the stable subject — opaque here, it only
 * has to be non-blank.
 */
public record UserId(@JsonValue String value) {

    public UserId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A user id must not be blank");
        }
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static UserId of(String value) {
        return new UserId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
