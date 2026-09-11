package ai.mindconnect.user.domain;

import ai.mindconnect.agent.EntityId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies an API token for listing and revoking. It never authenticates —
 * that is the secret's job. See {@link EntityId}.
 */
public record ApiTokenId(@JsonValue String value) implements EntityId {

    public ApiTokenId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ApiTokenId of(String value) {
        return new ApiTokenId(value);
    }

    /** A fresh, unique id. */
    public static ApiTokenId random() {
        return new ApiTokenId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
