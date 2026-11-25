package ai.mindconnect.conversation.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Identifies a message within its conversation. In JSON an id is its value. */
public record MessageId(@JsonValue String value) {

    public MessageId {
        IdFormat.checked(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static MessageId of(String value) {
        return new MessageId(value);
    }

    /** A fresh, unique id. */
    public static MessageId random() {
        return new MessageId(IdFormat.random());
    }

    @Override
    public String toString() {
        return value;
    }
}
