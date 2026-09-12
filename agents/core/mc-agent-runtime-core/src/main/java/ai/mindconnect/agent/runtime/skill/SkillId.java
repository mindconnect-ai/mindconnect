package ai.mindconnect.agent.runtime.skill;

import ai.mindconnect.agent.EntityId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies one stored skill. See {@link EntityId}: the value is a safe file
 * name, and in JSON an id is its plain value.
 */
public record SkillId(@JsonValue String value) implements EntityId {

    public SkillId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static SkillId of(String value) {
        return new SkillId(value);
    }

    /** A fresh, unique id. */
    public static SkillId random() {
        return new SkillId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
