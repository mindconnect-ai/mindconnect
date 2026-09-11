package ai.mindconnect.message.domain;

import ai.mindconnect.agent.EntityId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies one chat turn — a user message and everything the agent does to answer it. See {@link EntityId}.
 */
public record ChatTurnId(@JsonValue String value) implements EntityId {

    public ChatTurnId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ChatTurnId of(String value) {
        return new ChatTurnId(value);
    }

    /** A fresh, unique id. */
    public static ChatTurnId random() {
        return new ChatTurnId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
