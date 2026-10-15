package ai.mindconnect.credentials.domain;

import ai.mindconnect.agent.EntityId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Identifies one stored connection. See {@link EntityId}. */
public record ConnectionId(@JsonValue String value) implements EntityId {

    public ConnectionId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ConnectionId of(String value) {
        return new ConnectionId(value);
    }

    public static ConnectionId random() {
        return new ConnectionId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
