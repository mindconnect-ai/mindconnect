package ai.mindconnect.user.domain;

import ai.mindconnect.agent.EntityId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies one notification, for reading and dismissing it. See {@link EntityId}.
 */
public record NotificationId(@JsonValue String value) implements EntityId {

    public NotificationId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static NotificationId of(String value) {
        return new NotificationId(value);
    }

    /** A fresh, unique id. */
    public static NotificationId random() {
        return new NotificationId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
