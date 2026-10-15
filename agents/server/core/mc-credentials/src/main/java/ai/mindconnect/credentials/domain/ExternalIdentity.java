package ai.mindconnect.credentials.domain;

import java.util.Objects;

/**
 * Identification of a {@code (user, provider)} pair under which user
 * credentials are stored.
 *
 * <p>{@code userId} is the local user identifier from the host system (e.g.
 * the Keycloak subject claim). {@code providerName} is the
 * {@link OAuthProvider#name()} of the OAuth provider this credential belongs
 * to (e.g. {@code "atlassian"}, {@code "google"}).
 */
public record ExternalIdentity(String userId, String providerName) {
    public ExternalIdentity {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(providerName, "providerName");
        if (userId.isBlank())       throw new IllegalArgumentException("userId must not be blank");
        if (providerName.isBlank()) throw new IllegalArgumentException("providerName must not be blank");
    }
}
