package ai.mindconnect.conversation.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Identifies a participant within its conversation. In JSON an id is its value. */
public record ParticipantId(@JsonValue String value) {

    public ParticipantId {
        IdFormat.checked(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ParticipantId of(String value) {
        return new ParticipantId(value);
    }

    /** A fresh, unique id. */
    public static ParticipantId random() {
        return new ParticipantId(IdFormat.random());
    }

    @Override
    public String toString() {
        return value;
    }
}
