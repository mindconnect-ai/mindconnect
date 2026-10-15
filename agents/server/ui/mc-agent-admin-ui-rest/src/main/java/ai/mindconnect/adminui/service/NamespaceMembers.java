package ai.mindconnect.adminui.service;

import ai.mindconnect.agent.Email;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.domain.NamespaceRole;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.user.domain.User;
import ai.mindconnect.user.service.UserService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Membership as the screens change it: inviting somebody by e-mail address in a
 * role, promoting, demoting, removing, leaving, deleting the namespace. On top
 * of what the {@link NamespaceService} decides, this keeps the users'
 * remembered choice honest — someone who loses a namespace must not keep it as
 * the one they work in, or their live streams would go on showing it.
 *
 * <p><strong>An address, not an account.</strong> A namespace lists e-mail
 * addresses, so somebody can be invited before they have ever signed in: the
 * first sign-in under that address finds the membership already there. That is
 * also why nothing here refuses an unknown address — there is nothing to look
 * up, and nothing to tell the caller about who else exists on this
 * installation.
 */
@Service
public class NamespaceMembers {

    private final NamespaceService namespaces;
    private final UserService users;

    public NamespaceMembers(NamespaceService namespaces, UserService users) {
        this.namespaces = Objects.requireNonNull(namespaces, "namespaces");
        this.users = Objects.requireNonNull(users, "users");
    }

    /**
     * Puts {@code email} into {@code namespace} in {@code role}, as {@code inviter}.
     *
     * <p>Whether the caller is an admin is asked first, and a namespace that
     * does not exist is refused in the same words: otherwise anybody signed in
     * could learn which namespaces exist, and who is in them, by inviting.
     *
     * @return the address as it was listed
     * @throws IllegalArgumentException when {@code inviter} does not shape the namespace, the
     *                                  namespace is missing or the open default one, or the
     *                                  address is blank, malformed or already listed
     */
    public Email invite(Namespace namespace, UserId inviter, String email, NamespaceRole role) {
        NamespaceDefinition ns = namespaces.isAdmin(inviter, namespace)
                ? namespaces.find(namespace).orElse(null)
                : null;
        if (ns == null) {
            throw new IllegalArgumentException("Only an admin of '" + namespace.value() + "' may invite into it");
        }
        Email address = address(email);
        if (ns.admins().contains(address) || ns.users().contains(address)) {
            throw new IllegalArgumentException("'" + address + "' is already in '" + namespace.value() + "'");
        }
        namespaces.invite(namespace, inviter, address, role);
        return address;
    }

    /** {@code actor} makes {@code entry} an admin of the namespace. */
    public void promote(Namespace namespace, UserId actor, String entry) {
        namespaces.promote(namespace, actor, address(entry));
    }

    /** {@code actor} makes {@code entry} a user again; the creator stays an admin. */
    public void demote(Namespace namespace, UserId actor, String entry) {
        namespaces.demote(namespace, actor, address(entry));
    }

    /** {@code actor} removes {@code entry}; that person's remembered choice moves on if it was this namespace. */
    public void remove(Namespace namespace, UserId actor, String entry) {
        Email address = address(entry);
        namespaces.removeMember(namespace, actor, address);
        forget(namespace, Set.of(address));
    }

    /** {@code actor} leaves; the creator cannot, only delete. */
    public void leave(Namespace namespace, UserId actor) {
        namespaces.leave(namespace, actor);
        forget(namespace, Set.of(namespaces.actor(actor).email()));
    }

    /** Deletes the namespace with everything in it; every member's remembered choice moves on. */
    public void delete(Namespace namespace, UserId actor) {
        Set<Email> listed = namespaces.find(namespace).map(NamespaceMembers::everyone).orElse(Set.of());
        namespaces.delete(namespace, actor);
        forget(namespace, listed);
    }

    /**
     * What the form typed, as an address: a bare name gets the installation's
     * domain ({@code mindconnect.email-domain}), so that a company where every
     * account is {@code <name>@company.example} can be invited by name.
     */
    private Email address(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Enter the e-mail address of the person to invite");
        }
        return namespaces.address(email);
    }

    private static Set<Email> everyone(NamespaceDefinition ns) {
        Set<Email> all = new LinkedHashSet<>(ns.admins());
        all.addAll(ns.users());
        return all;
    }

    /**
     * Whoever is listed under {@code entries} and remembers {@code gone} as the
     * namespace they work in goes back to the default one. An address nobody has
     * signed in under yet has nothing to forget.
     *
     * <p>An account is matched through the same {@code actor} the namespace
     * service asks — so an account without a stored address, listed under its id
     * at the installation's domain, is found as well.
     */
    private void forget(Namespace gone, Set<Email> entries) {
        if (entries.isEmpty()) return;
        for (User user : users.all()) {
            if (!entries.contains(namespaces.actor(user.id()).email())) continue;
            if (users.activeNamespace(user.id()).filter(gone::equals).isPresent()) {
                users.selectNamespace(user.id(), namespaces.defaultNamespace());
            }
        }
    }
}
