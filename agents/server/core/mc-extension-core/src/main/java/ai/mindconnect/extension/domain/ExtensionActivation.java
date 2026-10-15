package ai.mindconnect.extension.domain;

import ai.mindconnect.agent.UserId;

import java.time.Instant;
import java.util.Objects;

/**
 * An admin's decision about one extension in one namespace: on or off, when,
 * and by whom. Only decisions are stored — a namespace that never decided
 * has the extension as the manifest's {@code enabledByDefault} says. Like
 * every store, the repository holding these is bound to one namespace;
 * nothing here names it.
 */
public record ExtensionActivation(ExtensionId extensionId, boolean enabled, Instant changedAt, UserId changedBy) {

    public ExtensionActivation {
        Objects.requireNonNull(extensionId, "extensionId");
        changedAt = changedAt == null ? Instant.now() : changedAt;
    }

    public static ExtensionActivation of(ExtensionId id, boolean enabled, UserId by) {
        return new ExtensionActivation(id, enabled, Instant.now(), by);
    }
}
