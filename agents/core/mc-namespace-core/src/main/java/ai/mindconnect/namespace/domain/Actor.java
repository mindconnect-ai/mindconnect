package ai.mindconnect.namespace.domain;

import ai.mindconnect.agent.Email;
import ai.mindconnect.agent.UserId;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Somebody asking something of a namespace: the id they sign in with, and the
 * e-mail address their account carries.
 *
 * <p>Namespaces list their people by address ({@link NamespaceDefinition}),
 * because an address exists before an account does. The id is what everything
 * else works with — a scope, a session, a task — so both travel together and
 * neither side has to look the other up.
 *
 * <p>An account whose provider gives no address can be in no namespace but the
 * installation's default one. That is the price of listing addresses, and it is
 * a configuration problem at the identity provider, not a case to work around
 * here.
 *
 * @param id    who they are to the stores; never null
 * @param email their address; null when the account carries none
 */
public record Actor(UserId id, Email email) {

    public Actor {
        Objects.requireNonNull(id, "An actor needs a user id");
    }

    public static Actor of(UserId id) {
        return new Actor(id, null);
    }

    public static Actor of(UserId id, Email email) {
        return new Actor(id, email);
    }

    /** With whatever an account carries as its address — not an address, no address. */
    public static Actor of(UserId id, String email) {
        return new Actor(id, Email.parse(email).orElse(null));
    }

    /**
     * Whether this actor is listed under {@code entry}. An entry without an
     * {@code @} is a user id: records written before namespaces listed people
     * by address (0.8.2 and older) name them that way, and they must keep
     * their namespaces after an upgrade. A bare name can no longer be written
     * ({@link Email#qualified} adds a domain), so this matches old entries only.
     */
    public boolean matches(Email entry) {
        if (entry == null) return false;
        if (entry.equals(email)) return true;
        return entry.value().indexOf('@') < 0
                && entry.value().equals(id.value().toLowerCase(Locale.ROOT));
    }

    public Optional<Email> address() {
        return Optional.ofNullable(email);
    }

    /** What to show: the address if there is one, else the id as signed in. */
    public String label() {
        return email != null ? email.value() : id.value();
    }
}
