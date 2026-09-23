package ai.mindconnect.extension.domain;

import ai.mindconnect.agent.UserId;

import java.time.Instant;
import java.util.Objects;

/**
 * A brand admin's decision about one extension for the whole brand: every
 * namespace that belongs to the brand — its own and the ones its people
 * made — has the extension as decided here, unless the namespace decided
 * for itself. {@code locked} takes that freedom away: a locked decision
 * holds in every namespace of the brand, whatever the namespace said.
 *
 * <p>Installation-wide, keyed by the brand's id (the id of the brand's own
 * namespace), because a brand is not a namespace but a set of them.
 */
public record BrandActivation(String brand, ExtensionId extensionId, boolean enabled, boolean locked,
                              Instant changedAt, UserId changedBy) {

    public BrandActivation {
        Objects.requireNonNull(brand, "brand");
        Objects.requireNonNull(extensionId, "extensionId");
        changedAt = changedAt == null ? Instant.now() : changedAt;
    }

    public static BrandActivation of(String brand, ExtensionId id, boolean enabled, boolean locked, UserId by) {
        return new BrandActivation(brand, id, enabled, locked, Instant.now(), by);
    }

    public BrandActivation withLocked(boolean locked, UserId by) {
        return new BrandActivation(brand, extensionId, enabled, locked, Instant.now(), by);
    }
}
