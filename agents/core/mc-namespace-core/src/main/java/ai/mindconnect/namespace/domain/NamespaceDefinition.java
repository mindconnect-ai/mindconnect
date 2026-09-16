package ai.mindconnect.namespace.domain;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import com.fasterxml.jackson.annotation.JsonAlias;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A namespace as the installation knows it: the partition everything else
 * lives in, and the users who may work there.
 *
 * <p>The id is the {@link Namespace} every adapter is bound to — the
 * directory name on disk, the partition column in Postgres. Members are the
 * n:m side of "a user can work in several namespaces": the record lists its
 * members, and a user's namespaces are the records that list them. The
 * creator is always a member, and the one who invites others.
 *
 * @param id          the namespace, as the stores know it
 * @param displayName what to show; null falls back to the id
 * @param createdBy   who created it; null for the installation's default namespace
 * @param createdAt   when it was created
 * @param members     who may work in it, the creator included
 * @param environment the namespace's variables — an API key everyone working here shares, say —
 *                    which {@code ${VAR}} placeholders resolve from after the user's own and
 *                    before the process's; values are stored encrypted, {@code enc:} prefixed
 */
public record NamespaceDefinition(
        Namespace id,
        String displayName,
        @JsonAlias("owner") UserId createdBy,
        Instant createdAt,
        Set<UserId> members,
        Map<String, String> environment
) {
    public NamespaceDefinition {
        Objects.requireNonNull(id, "A namespace needs an id");
        Objects.requireNonNull(createdAt, "A namespace needs a creation time");
        Set<UserId> all = new LinkedHashSet<>(members == null ? Set.of() : members);
        if (createdBy != null) all.add(createdBy);
        members = Set.copyOf(all);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

    /** A namespace without variables of its own. */
    public NamespaceDefinition(Namespace id, String displayName, UserId createdBy, Instant createdAt, Set<UserId> members) {
        this(id, displayName, createdBy, createdAt, members, null);
    }

    /** A namespace {@code creator} just created, with the creator as its only member. */
    public static NamespaceDefinition create(Namespace id, String displayName, UserId creator, Instant now) {
        return new NamespaceDefinition(id, displayName, creator, now, Set.of());
    }

    /** The name to show: the display name, else the id. */
    public String label() {
        return displayName != null && !displayName.isBlank() ? displayName : id.value();
    }

    public boolean isMember(UserId user) {
        return user != null && members.contains(user);
    }

    public boolean isCreator(UserId user) {
        return createdBy != null && createdBy.equals(user);
    }

    /** This namespace with {@code user} among its members. */
    public NamespaceDefinition withMember(UserId user) {
        Objects.requireNonNull(user, "user");
        if (members.contains(user)) return this;
        Set<UserId> all = new LinkedHashSet<>(members);
        all.add(user);
        return new NamespaceDefinition(id, displayName, createdBy, createdAt, all, environment);
    }

    /** This namespace without {@code user}; the creator cannot be removed. */
    public NamespaceDefinition withoutMember(UserId user) {
        if (user == null || !members.contains(user)) return this;
        if (isCreator(user)) throw new IllegalArgumentException("The creator of '" + id + "' cannot be removed");
        Set<UserId> all = new LinkedHashSet<>(members);
        all.remove(user);
        return new NamespaceDefinition(id, displayName, createdBy, createdAt, all, environment);
    }

    public NamespaceDefinition withDisplayName(String displayName) {
        return new NamespaceDefinition(id, displayName, createdBy, createdAt, members, environment);
    }

    /** This namespace with exactly {@code environment} as its variables ({@code null}: none). */
    public NamespaceDefinition withEnvironment(Map<String, String> environment) {
        return new NamespaceDefinition(id, displayName, createdBy, createdAt, members, environment);
    }
}
