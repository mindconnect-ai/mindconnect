package ai.mindconnect.message.domain;

import ai.mindconnect.agent.EntityId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies one participant of a conversation. See {@link EntityId}.
 */
public record ParticipantId(@JsonValue String value) implements EntityId {

    public ParticipantId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ParticipantId of(String value) {
        return new ParticipantId(value);
    }

    /** A fresh, unique id. */
    public static ParticipantId random() {
        return new ParticipantId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
