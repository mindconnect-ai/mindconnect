package ai.mindconnect.message.domain;

import ai.mindconnect.agent.EntityId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies one message. See {@link EntityId}.
 */
public record MessageId(@JsonValue String value) implements EntityId {

    public MessageId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static MessageId of(String value) {
        return new MessageId(value);
    }

    /** A fresh, unique id. */
    public static MessageId random() {
        return new MessageId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
