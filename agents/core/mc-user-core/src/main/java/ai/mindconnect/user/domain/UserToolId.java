package ai.mindconnect.user.domain;

import ai.mindconnect.agent.EntityId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Identifies one of a user's own tool bindings. See {@link EntityId}. */
public record UserToolId(@JsonValue String value) implements EntityId {

    public UserToolId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static UserToolId of(String value) {
        return new UserToolId(value);
    }

    public static UserToolId random() {
        return new UserToolId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
