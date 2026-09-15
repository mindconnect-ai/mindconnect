package ai.mindconnect.user.domain;

import ai.mindconnect.agent.UserId;

import java.time.Instant;
import java.util.Objects;

/**
 * A person who signs in to this installation. The id is the value every
 * session, workspace and task already carries — the identity provider's
 * {@code preferred_username} by default — so the record sits beside the
 * existing data instead of keying it differently.
 *
 * <p>Subject and issuer are the provider's stable identity. They are kept so
 * that a later change of the id claim can map old ids to new ones.
 *
 * @param id              the user id, as sessions and workspaces carry it
 * @param subject         the provider's {@code sub}; null when unknown (the dev user)
 * @param issuer          the provider's issuer; null when unknown
 * @param displayName     a name to show; null when the provider sent none
 * @param email           null when the provider sent none
 * @param createdAt       when the installation first saw the user
 * @param lastLoginAt     when the user last signed in or called the API, to the resolution
 *                        {@code UserService} records it with
 * @param activeNamespace the namespace the user last chose to work in — kept here so the
 *                        choice outlives a browser session and a restart; null until they chose one
 */
public record User(
        UserId id,
        String subject,
        String issuer,
        String displayName,
        String email,
        Instant createdAt,
        Instant lastLoginAt,
        String activeNamespace
) {
    public User {
        Objects.requireNonNull(id, "A user needs an id");
    }

    /** A user who has not chosen a namespace yet. */
    public User(UserId id, String subject, String issuer, String displayName, String email,
                Instant createdAt, Instant lastLoginAt) {
        this(id, subject, issuer, displayName, email, createdAt, lastLoginAt, null);
    }

    /** The name to show: the display name, else the id. */
    public String label() {
        return displayName != null && !displayName.isBlank() ? displayName : id.value();
    }

    /** This user with {@code activeNamespace} as the namespace they last chose to work in. */
    public User withActiveNamespace(String activeNamespace) {
        return new User(id, subject, issuer, displayName, email, createdAt, lastLoginAt, activeNamespace);
    }
}
