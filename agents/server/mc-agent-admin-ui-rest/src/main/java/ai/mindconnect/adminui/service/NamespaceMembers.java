package ai.mindconnect.adminui.service;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.user.domain.User;
import ai.mindconnect.user.service.UserService;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

/**
 * Membership as the screens change it: inviting a user by their user name,
 * removing one, leaving, deleting the namespace. On top of what the
 * {@link NamespaceService} decides, this keeps the users' remembered choice
 * honest — someone who loses a namespace must not keep it as the one they
 * work in, or their live streams would go on showing it.
 *
 * <p>The user name is the id every session and token already carries,
 * {@code preferred_username} at the identity provider. It has to belong to
 * a user this installation has seen: a typo, or someone who never signed in,
 * is refused rather than recorded as a member nobody can become. The
 * caller's right to invite is checked before the name is looked up, so the
 * endpoint tells nobody who is a user here.
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
     * Adds the user named {@code username} to {@code namespace}, as {@code inviter}.
     *
     * @return the invited user
     * @throws IllegalArgumentException when {@code inviter} did not create the namespace, the
     *                                  namespace is missing or the open default one, or the
     *                                  name is blank, unknown or already a member's
     */
    public User invite(Namespace namespace, UserId inviter, String username) {
        NamespaceDefinition ns = createdBy(namespace, inviter, "invites into");
        String name = username == null ? "" : username.strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Enter the user name of the person to invite");
        }
        User invitee = users.find(UserId.of(name)).orElseThrow(() -> new IllegalArgumentException(
                "No user '" + name + "' on this installation. A user appears after their first sign-in."));
        if (ns.isMember(invitee.id())) {
            throw new IllegalArgumentException("'" + name + "' is already a member of '" + namespace.value() + "'");
        }
        namespaces.invite(namespace, inviter, invitee.id());
        return invitee;
    }

    /** {@code actor} removes {@code member}; the member's remembered choice moves on if it was this namespace. */
    public void remove(Namespace namespace, UserId actor, UserId member) {
        namespaces.removeMember(namespace, actor, member);
        forget(namespace, Set.of(member));
    }

    /** {@code actor} leaves; the creator cannot, only delete. */
    public void leave(Namespace namespace, UserId actor) {
        namespaces.leave(namespace, actor);
        forget(namespace, Set.of(actor));
    }

    /** Deletes the namespace with everything in it; every member's remembered choice moves on. */
    public void delete(Namespace namespace, UserId actor) {
        Set<UserId> members = namespaces.find(namespace).map(NamespaceDefinition::members).orElse(Set.of());
        namespaces.delete(namespace, actor);
        forget(namespace, members);
    }

    private NamespaceDefinition createdBy(Namespace namespace, UserId actor, String verb) {
        if (namespace.equals(namespaces.defaultNamespace())) {
            throw new IllegalArgumentException("The default namespace is open to everyone; nobody " + verb + " it");
        }
        NamespaceDefinition ns = namespaces.find(namespace)
                .orElseThrow(() -> new IllegalArgumentException("No namespace '" + namespace.value() + "'"));
        if (!ns.isCreator(actor)) {
            throw new IllegalArgumentException("Only the creator of '" + namespace.value() + "' " + verb + " it");
        }
        return ns;
    }

    /** A user whose remembered namespace is {@code gone} goes back to the default one. */
    private void forget(Namespace gone, Set<UserId> affected) {
        for (UserId user : affected) {
            if (users.activeNamespace(user).filter(gone::equals).isPresent()) {
                users.selectNamespace(user, namespaces.defaultNamespace());
            }
        }
    }
}
