package ai.mindconnect.namespace.service;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.port.out.NamespaceRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What the installation does with namespaces: creates them, invites users
 * into them, and answers which namespaces a user may work in.
 *
 * <p>The <em>default</em> namespace ({@code local} unless configured) is
 * special in one way: it is open to every signed-in user, so an
 * installation that never creates another namespace behaves exactly as it
 * did before namespaces existed. It gets a record on first use, owned by
 * nobody.
 *
 * <p>A namespace id is what the stores partition by — a directory name, a
 * column value, part of a URL — so it is kept simple: lower-case letters,
 * digits, {@code -} and {@code _}, at most 64 characters. Only the creator
 * invites, removes members and renames; any member may leave.
 */
public class NamespaceService {

    /** What an id may look like: safe as a directory name and as a path segment. */
    public static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private final NamespaceRepository namespaces;
    private final Namespace defaultNamespace;
    private final Clock clock;

    public NamespaceService(NamespaceRepository namespaces, Namespace defaultNamespace) {
        this(namespaces, defaultNamespace, Clock.systemUTC());
    }

    public NamespaceService(NamespaceRepository namespaces, Namespace defaultNamespace, Clock clock) {
        this.namespaces = Objects.requireNonNull(namespaces, "namespaces");
        this.defaultNamespace = Objects.requireNonNull(defaultNamespace, "defaultNamespace");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The namespace every user may work in without being invited. */
    public Namespace defaultNamespace() {
        return defaultNamespace;
    }

    /** The default namespace's record, created on first use. */
    public NamespaceDefinition defaultDefinition() {
        return namespaces.findById(defaultNamespace).orElseGet(() -> {
            NamespaceDefinition created = new NamespaceDefinition(defaultNamespace, null, null, Instant.now(clock), null);
            namespaces.save(created);
            return created;
        });
    }

    public Optional<NamespaceDefinition> find(Namespace id) {
        if (defaultNamespace.equals(id)) return Optional.of(defaultDefinition());
        return namespaces.findById(id);
    }

    /** Every namespace of the installation — for an operator's overview, not for choosing where to work. */
    public List<NamespaceDefinition> all() {
        defaultDefinition();
        return namespaces.findAll();
    }

    /** The namespaces {@code user} may work in: the default one first, then the memberships. */
    public List<NamespaceDefinition> forUser(UserId user) {
        List<NamespaceDefinition> out = new ArrayList<>();
        out.add(defaultDefinition());
        for (NamespaceDefinition ns : namespaces.findByMember(user)) {
            if (!ns.id().equals(defaultNamespace)) out.add(ns);
        }
        return out;
    }

    /** Whether {@code user} may work in {@code id}: the default namespace, or one they are a member of. */
    public boolean canAccess(UserId user, Namespace id) {
        if (defaultNamespace.equals(id)) return true;
        return namespaces.findById(id).map(ns -> ns.isMember(user)).orElse(false);
    }

    /**
     * Creates an empty namespace, with {@code creator} as its first member.
     *
     * @throws IllegalArgumentException when the id is malformed or taken
     */
    public NamespaceDefinition create(String id, String displayName, UserId creator) {
        Objects.requireNonNull(creator, "creator");
        String value = id == null ? "" : id.strip();
        if (!ID.matcher(value).matches()) {
            throw new IllegalArgumentException("A namespace id is 1–64 lower-case letters, digits, '-' or '_', got '" + id + "'");
        }
        Namespace namespace = new Namespace(value);
        if (defaultNamespace.equals(namespace) || namespaces.findById(namespace).isPresent()) {
            throw new IllegalArgumentException("Namespace '" + value + "' already exists");
        }
        String label = displayName == null || displayName.isBlank() ? null : displayName.strip();
        NamespaceDefinition created = NamespaceDefinition.create(namespace, label, creator, Instant.now(clock));
        namespaces.save(created);
        return created;
    }

    /**
     * Adds {@code invitee} to {@code id}. Only the creator invites.
     *
     * @throws IllegalArgumentException when the namespace does not exist, is the open default one,
     *                                  or {@code inviter} did not create it
     */
    public NamespaceDefinition invite(Namespace id, UserId inviter, UserId invitee) {
        Objects.requireNonNull(invitee, "invitee");
        NamespaceDefinition ns = memberOnly(id, inviter, "invite into");
        if (!ns.isCreator(inviter)) throw new IllegalArgumentException("Only the creator of '" + id + "' invites");
        NamespaceDefinition updated = ns.withMember(invitee);
        if (updated != ns) namespaces.save(updated);
        return updated;
    }

    /**
     * Removes {@code member} from {@code id}. The creator may remove anyone but themselves;
     * any member may remove themselves.
     */
    public NamespaceDefinition removeMember(Namespace id, UserId actor, UserId member) {
        Objects.requireNonNull(member, "member");
        NamespaceDefinition ns = memberOnly(id, actor, "change");
        if (!ns.isCreator(actor) && !actor.equals(member)) {
            throw new IllegalArgumentException("Only the creator of '" + id + "' removes other members");
        }
        NamespaceDefinition updated = ns.withoutMember(member);
        if (updated != ns) namespaces.save(updated);
        return updated;
    }

    public NamespaceDefinition rename(Namespace id, UserId actor, String displayName) {
        NamespaceDefinition ns = memberOnly(id, actor, "rename");
        if (!ns.isCreator(actor)) throw new IllegalArgumentException("Only the creator renames '" + id + "'");
        NamespaceDefinition updated = ns.withDisplayName(displayName == null || displayName.isBlank() ? null : displayName.strip());
        namespaces.save(updated);
        return updated;
    }

    private NamespaceDefinition memberOnly(Namespace id, UserId actor, String verb) {
        Objects.requireNonNull(actor, "actor");
        if (defaultNamespace.equals(id)) {
            throw new IllegalArgumentException("The default namespace '" + id + "' is open to everyone; there is nothing to " + verb);
        }
        NamespaceDefinition ns = namespaces.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No namespace '" + id + "'"));
        if (!ns.isMember(actor)) {
            throw new IllegalArgumentException("'" + actor.value() + "' is not a member of '" + id + "'");
        }
        return ns;
    }
}
