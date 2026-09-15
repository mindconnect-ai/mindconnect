package ai.mindconnect.adminui.service;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.user.domain.User;
import ai.mindconnect.user.service.UserService;

import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Inviting a user into a namespace by their user name — the id every
 * session and token already carries, {@code preferred_username} at the
 * identity provider. The name has to belong to a user this installation
 * has seen: a typo, or someone who never signed in, is refused rather than
 * recorded as a member nobody can become.
 */
@Service
public class NamespaceInvitations {

    private final NamespaceService namespaces;
    private final UserService users;

    public NamespaceInvitations(NamespaceService namespaces, UserService users) {
        this.namespaces = Objects.requireNonNull(namespaces, "namespaces");
        this.users = Objects.requireNonNull(users, "users");
    }

    /**
     * Adds the user named {@code username} to {@code namespace}, as {@code inviter}.
     *
     * @return the invited user
     * @throws IllegalArgumentException when the name is blank or unknown, or the namespace
     *                                  refuses (not created by {@code inviter}, the open default one, missing)
     */
    public User invite(Namespace namespace, UserId inviter, String username) {
        String name = username == null ? "" : username.strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Enter the user name of the person to invite");
        }
        User invitee = users.find(UserId.of(name)).orElseThrow(() -> new IllegalArgumentException(
                "No user '" + name + "' on this installation. A user appears after their first sign-in."));
        NamespaceDefinition ns = namespaces.find(namespace)
                .orElseThrow(() -> new IllegalArgumentException("No namespace '" + namespace.value() + "'"));
        if (ns.isMember(invitee.id())) {
            throw new IllegalArgumentException("'" + name + "' is already a member of '" + namespace.value() + "'");
        }
        namespaces.invite(namespace, inviter, invitee.id());
        return invitee;
    }
}
