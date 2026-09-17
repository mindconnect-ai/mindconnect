package ai.mindconnect.namespace.port.out;

import ai.mindconnect.agent.Email;
import ai.mindconnect.agent.UserId;

import java.util.Optional;

/**
 * The e-mail address behind a user id.
 *
 * <p>Namespaces list people by e-mail, but everything that asks a namespace a
 * question — a filter binding a scope, a screen rendering a list — holds a
 * {@link UserId}. This is the one step between the two, and the reason the
 * namespace service does not depend on the user store: the server hands in the
 * lookup, the domain stays with its own vocabulary.
 *
 * <p>An installation without a user store, or an account whose provider gives
 * no address, answers {@link Optional#empty()}; the namespace service then
 * falls back to the user's id at the installation's domain, so that such an
 * account can be in a namespace at all.
 */
@FunctionalInterface
public interface UserEmails {

    Optional<Email> emailOf(UserId id);

    /** Nobody has a stored address: every actor falls back to its id at the installation's domain. */
    static UserEmails none() {
        return id -> Optional.empty();
    }
}
