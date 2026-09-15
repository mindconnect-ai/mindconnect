package ai.mindconnect.namespace.domain;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A namespace as the installation knows it: the partition everything else
 * lives in, and the users who may work there.
 *
 * <p>The id is the {@link Namespace} every adapter is bound to — the
 * directory name on disk, the partition column in Postgres. Members are the
 * n:m side of "a user can work in several namespaces": the record lists its
 * members, and a user's namespaces are the records that list them. The owner
 * created the namespace and is always a member.
 *
 * @param id          the namespace, as the stores know it
 * @param displayName what to show; null falls back to the id
 * @param owner       who created it; null for the installation's default namespace
 * @param createdAt   when it was created
 * @param members     who may work in it, the owner included
 */
public record NamespaceDefinition(
        Namespace id,
        String displayName,
        UserId owner,
        Instant createdAt,
        Set<UserId> members
) {
    public NamespaceDefinition {
        Objects.requireNonNull(id, "A namespace needs an id");
        Objects.requireNonNull(createdAt, "A namespace needs a creation time");
        Set<UserId> all = new LinkedHashSet<>(members == null ? Set.of() : members);
        if (owner != null) all.add(owner);
        members = Set.copyOf(all);
    }

    /** A namespace {@code owner} just created, with the owner as its only member. */
    public static NamespaceDefinition create(Namespace id, String displayName, UserId owner, Instant now) {
        return new NamespaceDefinition(id, displayName, owner, now, Set.of());
    }

    /** The name to show: the display name, else the id. */
    public String label() {
        return displayName != null && !displayName.isBlank() ? displayName : id.value();
    }

    public boolean isMember(UserId user) {
        return user != null && members.contains(user);
    }

    public boolean isOwner(UserId user) {
        return owner != null && owner.equals(user);
    }

    /** This namespace with {@code user} among its members. */
    public NamespaceDefinition withMember(UserId user) {
        Objects.requireNonNull(user, "user");
        if (members.contains(user)) return this;
        Set<UserId> all = new LinkedHashSet<>(members);
        all.add(user);
        return new NamespaceDefinition(id, displayName, owner, createdAt, all);
    }

    /** This namespace without {@code user}; the owner cannot be removed. */
    public NamespaceDefinition withoutMember(UserId user) {
        if (user == null || !members.contains(user)) return this;
        if (isOwner(user)) throw new IllegalArgumentException("The owner of '" + id + "' cannot be removed");
        Set<UserId> all = new LinkedHashSet<>(members);
        all.remove(user);
        return new NamespaceDefinition(id, displayName, owner, createdAt, all);
    }

    public NamespaceDefinition withDisplayName(String displayName) {
        return new NamespaceDefinition(id, displayName, owner, createdAt, members);
    }
}
