package ai.mindconnect.agent;

/**
 * Identifies a chat session. See {@link EntityId}.
 */
public record SessionId(String value) implements EntityId {

    public SessionId {
        EntityId.check(value);
    }

    public static SessionId of(String value) {
        return new SessionId(value);
    }

    /** A fresh, unique id. */
    public static SessionId random() {
        return new SessionId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
