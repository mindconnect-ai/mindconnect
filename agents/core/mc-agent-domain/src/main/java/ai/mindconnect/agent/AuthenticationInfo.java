package ai.mindconnect.agent;

import java.util.List;

/**
 * Carries the authenticated user's identity and authorization context.
 * <p>
 * Server-internal domain object — derived at the transport boundary (e.g. by a Spring
 * Security filter that converts an {@code Authentication} into this), then passed
 * explicitly to ports and services. Never put on the wire and never set by clients;
 * clients authenticate using standard transport mechanisms (Bearer tokens, OAuth).
 */
public record AuthenticationInfo(
        UserId userId,
        Namespace namespace,
        List<String> roles
) {

    public AuthenticationInfo {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (namespace == null) {
            throw new IllegalArgumentException("namespace must not be null");
        }
        roles = roles != null ? List.copyOf(roles) : List.of();
    }

    public static AuthenticationInfo of(UserId userId, Namespace namespace) {
        return new AuthenticationInfo(userId, namespace, List.of());
    }

    public static AuthenticationInfo of(UserId userId, Namespace namespace, List<String> roles) {
        return new AuthenticationInfo(userId, namespace, roles);
    }
}
