package ai.mindconnect.agent;

import java.util.Map;
import java.util.Optional;

/**
 * Who is working where: the context a unit of work runs in.
 *
 * <p>The {@link #namespace()} is the storage partition every adapter call
 * goes to — the one part the runtime itself depends on. The {@link #user()}
 * is the identity behind the work when there is one (a request, a chat
 * turn); library callers and start-up routines have none. The
 * {@link #attributes()} are the host's — a selected group, an organisation,
 * a role — carried along for hosts that scope more finely than the
 * namespace, and ignored by the runtime.
 *
 * <p>A scope is a value: build a new one to change it.
 */
public record Scope(Namespace namespace, UserId user, Map<String, String> attributes) {

    public Scope {
        if (namespace == null) throw new IllegalArgumentException("namespace must not be null");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** Work in {@code namespace} on nobody's behalf. */
    public static Scope of(Namespace namespace) {
        return new Scope(namespace, null, Map.of());
    }

    /** Work in {@code namespace} on behalf of {@code user}. */
    public static Scope of(Namespace namespace, UserId user) {
        return new Scope(namespace, user, Map.of());
    }

    /** {@link Namespace#DEFAULT} on nobody's behalf — what a library gets without asking. */
    public static Scope local() {
        return of(Namespace.DEFAULT);
    }

    /** The user behind the work, if any. */
    public Optional<UserId> userIfAny() {
        return Optional.ofNullable(user);
    }

    public Scope withUser(UserId user) {
        return new Scope(namespace, user, attributes);
    }

    public Scope withNamespace(Namespace namespace) {
        return new Scope(namespace, user, attributes);
    }

    /** This scope plus one host attribute; an existing value for {@code key} is replaced. */
    public Scope withAttribute(String key, String value) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("attribute key must not be blank");
        if (value == null) throw new IllegalArgumentException("attribute value must not be null");
        var copy = new java.util.LinkedHashMap<>(attributes);
        copy.put(key, value);
        return new Scope(namespace, user, copy);
    }

    public Optional<String> attribute(String key) {
        return Optional.ofNullable(attributes.get(key));
    }

    @Override
    public String toString() {
        return namespace.value() + (user == null ? "" : "/" + user.value())
                + (attributes.isEmpty() ? "" : attributes.toString());
    }
}
