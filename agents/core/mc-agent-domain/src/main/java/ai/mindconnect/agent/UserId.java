package ai.mindconnect.agent;

/**
 * Identifies a user. Not an {@link EntityId}: the value is whatever the
 * identity provider hands over as the stable subject — opaque here, it only
 * has to be non-blank.
 */
public record UserId(String value) {

    public UserId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A user id must not be blank");
        }
    }

    public static UserId of(String value) {
        return new UserId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
