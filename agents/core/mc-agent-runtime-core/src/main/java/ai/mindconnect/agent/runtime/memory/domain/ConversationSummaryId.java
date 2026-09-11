package ai.mindconnect.agent.runtime.memory.domain;

import ai.mindconnect.agent.EntityId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies one conversation summary. See {@link EntityId}.
 */
public record ConversationSummaryId(@JsonValue String value) implements EntityId {

    public ConversationSummaryId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ConversationSummaryId of(String value) {
        return new ConversationSummaryId(value);
    }

    /** A fresh, unique id. */
    public static ConversationSummaryId random() {
        return new ConversationSummaryId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
