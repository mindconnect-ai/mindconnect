package ai.mindconnect.scope;

import java.util.Optional;

/**
 * Whose data a call works on: the namespace it is kept in, and the user it is
 * done for, where there is one. Plain strings, so that an area needs no type
 * of whoever names them.
 *
 * @param namespace never blank
 * @param user      null where nobody in particular acts, as at start-up
 */
public record Tenant(String namespace, String user) {

    /** The namespace of an installation that has only one. */
    public static final String LOCAL = "local";

    public Tenant {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("A tenant names its namespace");
        }
    }

    public static Tenant of(String namespace) {
        return new Tenant(namespace, null);
    }

    public Optional<String> userIfAny() {
        return Optional.ofNullable(user);
    }
}
