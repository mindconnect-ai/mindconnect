package ai.mindconnect.user.domain;

import ai.mindconnect.agent.UserId;

import java.time.Instant;
import java.util.Objects;

/**
 * A personal API token: a secret its owner hands to a program so that the
 * program acts as the owner against the REST API. Only the SHA-256 hash of the
 * secret is stored; the secret itself is shown once, when the token is issued.
 *
 * @param id         addresses the token for listing and revoking; never authenticates
 * @param userId     whose token it is — a request authenticated by it runs as this user
 * @param name       what the owner called it ("CI", "laptop")
 * @param tokenHash  hex SHA-256 of the secret
 * @param hint       the start of the secret, enough to recognise it in a list
 * @param createdAt  when it was issued
 * @param expiresAt  from this instant on it no longer authenticates; null = no expiry
 * @param lastUsedAt the last successful authentication, to the resolution the service
 *                   records it with; null = never used
 */
public record ApiToken(
        ApiTokenId id,
        UserId userId,
        String name,
        String tokenHash,
        String hint,
        Instant createdAt,
        Instant expiresAt,
        Instant lastUsedAt
) {

    public ApiToken {
        Objects.requireNonNull(id, "An API token needs an id");
        Objects.requireNonNull(userId, "An API token needs an owner");
        Objects.requireNonNull(tokenHash, "An API token needs a hash");
    }

    /** Whether the token no longer authenticates at {@code now}. */
    public boolean expiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** The same token, last used at {@code at}. */
    public ApiToken usedAt(Instant at) {
        return new ApiToken(id, userId, name, tokenHash, hint, createdAt, expiresAt, at);
    }
}
