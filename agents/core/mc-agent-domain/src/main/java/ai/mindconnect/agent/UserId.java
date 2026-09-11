package ai.mindconnect.agent;

/**
 * Identifies a user. Not a {@link NamespacedId}: a user stands above tenants
 * and may be a member of several, so the id carries no namespace — the pair
 * {@code (Namespace, UserId)} is what names "this person in this tenant".
 *
 * <p>The value is whatever the identity provider hands over as the stable
 * subject; it is opaque here and only has to be non-blank.
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
