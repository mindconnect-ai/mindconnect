package ai.mindconnect.conversation.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Identifies a conversation. In JSON an id is its value. */
public record ConversationId(@JsonValue String value) {

    public ConversationId {
        IdFormat.checked(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ConversationId of(String value) {
        return new ConversationId(value);
    }

    /** A fresh, unique id. */
    public static ConversationId random() {
        return new ConversationId(IdFormat.random());
    }

    @Override
    public String toString() {
        return value;
    }
}
