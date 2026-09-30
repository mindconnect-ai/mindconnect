package ai.mindconnect.llm.domain;

import ai.mindconnect.agent.EntityId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies one price period of an LLM config. See {@link EntityId}.
 */
public record LlmPriceId(@JsonValue String value) implements EntityId {

    public LlmPriceId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static LlmPriceId of(String value) {
        return new LlmPriceId(value);
    }

    /** A fresh, unique id. */
    public static LlmPriceId random() {
        return new LlmPriceId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
