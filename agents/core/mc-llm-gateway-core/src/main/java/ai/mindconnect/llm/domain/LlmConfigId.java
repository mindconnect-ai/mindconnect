package ai.mindconnect.llm.domain;

import ai.mindconnect.agent.EntityId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies an LLM configuration. See {@link EntityId}.
 */
public record LlmConfigId(@JsonValue String value) implements EntityId {

    public LlmConfigId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static LlmConfigId of(String value) {
        return new LlmConfigId(value);
    }

    /** A fresh, unique id. */
    public static LlmConfigId random() {
        return new LlmConfigId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
