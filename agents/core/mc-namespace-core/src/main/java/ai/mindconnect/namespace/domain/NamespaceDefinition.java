package ai.mindconnect.namespace.domain;

import ai.mindconnect.agent.Email;
import ai.mindconnect.agent.Namespace;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A namespace as the installation knows it: the partition everything else
 * lives in, and who may work there in which role.
 *
 * <p>The id is the {@link Namespace} every adapter is bound to — the
 * directory name on disk, the partition column in Postgres. Membership is the
 * n:m side of "a user can work in several namespaces": the record lists its
 * people, and a user's namespaces are the records that list them.
 *
 * <p><strong>People are listed by e-mail</strong>, in one of two lists.
 * {@link #admins()} shape the namespace, {@link #users()} work in it — see
 * {@link NamespaceRole}. An e-mail exists before an account does, so a
 * namespace can be prepared for people who have never signed in; nothing has
 * to be pending, and nothing has to be sent. Addresses are lower-cased and
 * kept as {@link Email} values, so a list in JSON is plain addresses.
 *
 * <p>The creator is one entry among the admins, and the only one that cannot
 * be taken out: a namespace without an admin would be nobody's to clean up.
 * Deleting stays theirs alone — an admin they promoted shapes the namespace,
 * but does not get to throw away everyone's work.
 *
 * @param id          the namespace, as the stores know it
 * @param displayName what to show; null falls back to the id
 * @param createdBy   who created it, as an entry; null for the installation's default namespace
 * @param createdAt   when it was created
 * @param admins      who shapes it, the creator included; never null
 * @param users       who works in it; never null, and never anyone who is also an admin
 * @param environment the namespace's variables — an API key everyone working here shares, say —
 *                    which {@code ${VAR}} placeholders resolve from after the user's own and
 *                    before the process's; values are stored encrypted, {@code enc:} prefixed
 */
public record NamespaceDefinition(
        Namespace id,
        String displayName,
        @JsonAlias("owner") Email createdBy,
        Instant createdAt,
        Set<Email> admins,
        Set<Email> users,
        Map<String, String> environment
) {
    public NamespaceDefinition {
        Objects.requireNonNull(id, "A namespace needs an id");
        Objects.requireNonNull(createdAt, "A namespace needs a creation time");
        Set<Email> shapers = entries(admins);
        if (createdBy != null) shapers.add(createdBy);
        Set<Email> workers = entries(users);
        workers.removeAll(shapers);
        admins = Set.copyOf(shapers);
        users = Set.copyOf(workers);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

    /**
     * What Jackson builds a record from, so that documents written before the
     * two lists existed keep working: their {@code members} become users, and
     * {@code createdBy} (or its older name {@code owner}) stays the creator and
     * therefore an admin. Nothing on disk is rewritten until the namespace
     * changes for another reason.
     */
    @JsonCreator
    static NamespaceDefinition fromJson(
            @JsonProperty("id") Namespace id,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("createdBy") @JsonAlias("owner") Email createdBy,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("admins") Set<Email> admins,
            @JsonProperty("users") Set<Email> users,
            @JsonProperty("members") Set<Email> legacyMembers,
            @JsonProperty("environment") Map<String, String> environment) {
        Set<Email> workers = entries(users);
        workers.addAll(entries(legacyMembers));
        return new NamespaceDefinition(id, displayName, createdBy, createdAt, admins, workers, environment);
    }

    /** A namespace without variables of its own. */
    public NamespaceDefinition(Namespace id, String displayName, Email createdBy, Instant createdAt,
                               Set<Email> admins, Set<Email> users) {
        this(id, displayName, createdBy, createdAt, admins, users, null);
    }

    /** A namespace {@code creator} just created, with them as its only admin and nobody else in it. */
    public static NamespaceDefinition create(Namespace id, String displayName, Email creator, Instant now) {
        return new NamespaceDefinition(id, displayName, creator, now, Set.of(), Set.of());
    }

    /**
     * A namespace the installation brings with it — a brand's, say — whose admins
     * come from configuration rather than from whoever happened to arrive first.
     * The <em>first</em> of them is its creator, so that deleting it has an owner;
     * that is why this takes a list and not a set.
     */
    public static NamespaceDefinition create(Namespace id, String displayName, List<Email> admins, Instant now) {
        List<Email> shapers = admins == null ? List.of() : admins.stream().filter(Objects::nonNull).toList();
        Email creator = shapers.stream().findFirst().orElse(null);
        return new NamespaceDefinition(id, displayName, creator, now, new LinkedHashSet<>(shapers), Set.of());
    }

    /** The name to show: the display name, else the id. */
    public String label() {
        return displayName != null && !displayName.isBlank() ? displayName : id.value();
    }

    /** What {@code who} may do here, or empty when this namespace does not list them. */
    @JsonIgnore
    public Optional<NamespaceRole> role(Actor who) {
        if (who == null) return Optional.empty();
        if (matches(admins, who)) return Optional.of(NamespaceRole.ADMIN);
        if (matches(users, who)) return Optional.of(NamespaceRole.USER);
        return Optional.empty();
    }

    public boolean isAdmin(Actor who) {
        return role(who).filter(NamespaceRole.ADMIN::equals).isPresent();
    }

    public boolean isMember(Actor who) {
        return role(who).isPresent();
    }

    /** Whether {@code who} created this namespace — the one right an admin cannot be given. */
    public boolean isCreator(Actor who) {
        return createdBy != null && who != null && who.matches(createdBy);
    }

    /** The address {@code who} is listed under here, or empty when they are not in it. */
    public Optional<Email> entryOf(Actor who) {
        Objects.requireNonNull(who, "who");
        for (Set<Email> list : List.of(admins, users)) {
            for (Email entry : list) {
                if (who.matches(entry)) return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** This namespace with {@code entry} among its admins, taken out of the users if it was there. */
    public NamespaceDefinition withAdmin(Email entry) {
        Email value = require(entry);
        if (admins.contains(value)) return this;
        Set<Email> shapers = entries(admins);
        shapers.add(value);
        Set<Email> workers = entries(users);
        workers.remove(value);
        return new NamespaceDefinition(id, displayName, createdBy, createdAt, shapers, workers, environment);
    }

    /**
     * This namespace with {@code entry} among its users. An admin stays an admin —
     * {@link #demote} is how that is said, so that inviting somebody who already
     * shapes the namespace never quietly takes it away.
     */
    public NamespaceDefinition withUser(Email entry) {
        Email value = require(entry);
        if (admins.contains(value) || users.contains(value)) return this;
        Set<Email> workers = entries(users);
        workers.add(value);
        return new NamespaceDefinition(id, displayName, createdBy, createdAt, admins, workers, environment);
    }

    /** This namespace with {@code entry} in the given role. */
    public NamespaceDefinition with(Email entry, NamespaceRole role) {
        return role == NamespaceRole.ADMIN ? withAdmin(entry) : withUser(entry);
    }

    /** This namespace with {@code entry} moved from the admins to the users; the creator stays. */
    public NamespaceDefinition demote(Email entry) {
        Email value = require(entry);
        if (!admins.contains(value)) return this;
        if (value.equals(createdBy)) {
            throw new IllegalArgumentException("The creator of '" + id.value() + "' stays an admin");
        }
        Set<Email> shapers = entries(admins);
        shapers.remove(value);
        Set<Email> workers = entries(users);
        workers.add(value);
        return new NamespaceDefinition(id, displayName, createdBy, createdAt, shapers, workers, environment);
    }

    /** This namespace without {@code entry} in either list; the creator cannot be removed. */
    public NamespaceDefinition without(Email entry) {
        Email value = entry;
        if (value == null || !(admins.contains(value) || users.contains(value))) return this;
        if (value.equals(createdBy)) {
            throw new IllegalArgumentException("The creator of '" + id.value() + "' cannot be removed");
        }
        Set<Email> shapers = entries(admins);
        shapers.remove(value);
        Set<Email> workers = entries(users);
        workers.remove(value);
        return new NamespaceDefinition(id, displayName, createdBy, createdAt, shapers, workers, environment);
    }

    public NamespaceDefinition withDisplayName(String displayName) {
        return new NamespaceDefinition(id, displayName, createdBy, createdAt, admins, users, environment);
    }

    /** This namespace with exactly {@code environment} as its variables ({@code null}: none). */
    public NamespaceDefinition withEnvironment(Map<String, String> environment) {
        return new NamespaceDefinition(id, displayName, createdBy, createdAt, admins, users, environment);
    }

    private static boolean matches(Set<Email> list, Actor who) {
        for (Email entry : list) {
            if (who.matches(entry)) return true;
        }
        return false;
    }

    /** A mutable, order-keeping copy without the nulls. */
    private static Set<Email> entries(Set<Email> raw) {
        Set<Email> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (Email entry : raw) {
            if (entry != null) out.add(entry);
        }
        return out;
    }

    private static Email require(Email entry) {
        if (entry == null) throw new IllegalArgumentException("An entry needs an e-mail address");
        return entry;
    }
}
