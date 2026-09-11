package ai.mindconnect.message.domain;

import ai.mindconnect.agent.EntityId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies a conversation — the message history a session writes into. See {@link EntityId}.
 */
public record ConversationId(@JsonValue String value) implements EntityId {

    public ConversationId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ConversationId of(String value) {
        return new ConversationId(value);
    }

    /** A fresh, unique id. */
    public static ConversationId random() {
        return new ConversationId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
