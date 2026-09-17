package ai.mindconnect.namespace.service;

import ai.mindconnect.agent.Email;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.NamespacePurge;
import ai.mindconnect.agent.NamespaceRouted;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.namespace.domain.Actor;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.domain.NamespaceRole;
import ai.mindconnect.namespace.port.out.NamespaceRepository;
import ai.mindconnect.namespace.port.out.UserEmails;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the installation does with namespaces: creates them, invites people
 * into them in a role, and answers who may work where and as what.
 *
 * <p>The <em>default</em> namespace ({@code local} unless configured) is
 * special in one way: it is open to every signed-in user, who is an admin
 * there, so an installation that never creates another namespace behaves
 * exactly as it did before namespaces existed. It gets a record on first use,
 * owned by nobody.
 *
 * <p>A namespace id is what the stores partition by — a directory name, a
 * column value, part of a URL — so it is kept simple: lower-case letters,
 * digits, {@code -} and {@code _}, at most 64 characters, and not a name the
 * installation uses for itself ({@code system} is where users, tokens and the
 * namespaces live).
 *
 * <p>Who may do what follows {@link NamespaceRole}: admins invite, promote,
 * rename, set the variables and shape what the namespace holds; users work in
 * it. Deleting stays with the creator alone. Everything here takes a
 * {@link UserId}, because that is what a request carries, and turns it into an
 * {@link Actor} with the e-mail from {@link UserEmails} — the namespaces
 * themselves list people by {@link Email}.
 *
 * <p>Deleting a namespace runs every {@link NamespacePurge} the service was
 * given — the stores remove what they hold for it — then drops the record and
 * evicts the namespace from every routed port. A purge that fails keeps the
 * record, so the deletion can be tried again.
 */
public class NamespaceService {

    /** What an id may look like: safe as a directory name and as a path segment. */
    public static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    /** Ids no namespace may take: the installation's own directory, and the URL prefixes. */
    public static final Set<String> RESERVED = Set.of("system", "ns", "api", "admin", "v1");

    private final NamespaceRepository namespaces;
    private final Namespace defaultNamespace;
    private final Clock clock;
    private final List<NamespacePurge> purges;
    private final UserEmails emails;
    private final String emailDomain;
    private final List<Email> defaultAdmins;
    private final java.util.concurrent.ConcurrentHashMap<Namespace, Object> locks = new java.util.concurrent.ConcurrentHashMap<>();

    public NamespaceService(NamespaceRepository namespaces, Namespace defaultNamespace) {
        this(namespaces, defaultNamespace, Clock.systemUTC());
    }

    public NamespaceService(NamespaceRepository namespaces, Namespace defaultNamespace, Clock clock) {
        this(namespaces, defaultNamespace, clock, List.of());
    }

    /** @param purges what each store does with a deleted namespace's data; may be empty */
    public NamespaceService(NamespaceRepository namespaces, Namespace defaultNamespace, Clock clock,
                            List<NamespacePurge> purges) {
        this(namespaces, defaultNamespace, clock, purges, UserEmails.none(), DEFAULT_EMAIL_DOMAIN);
    }

    public NamespaceService(NamespaceRepository namespaces, Namespace defaultNamespace, Clock clock,
                            List<NamespacePurge> purges, UserEmails emails, String emailDomain) {
        this(namespaces, defaultNamespace, clock, purges, emails, emailDomain, List.of());
    }

    /**
     * @param emails      how a user id becomes the address namespaces list people under
     * @param emailDomain what a bare name becomes an address with — the domain of the
     *                    installation, used for an account whose provider gives no
     *                    address at all and for an invitation written as a name
     */
    public NamespaceService(NamespaceRepository namespaces, Namespace defaultNamespace, Clock clock,
                            List<NamespacePurge> purges, UserEmails emails, String emailDomain,
                            List<Email> defaultAdmins) {
        this.namespaces = Objects.requireNonNull(namespaces, "namespaces");
        this.defaultNamespace = Objects.requireNonNull(defaultNamespace, "defaultNamespace");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.purges = List.copyOf(Objects.requireNonNull(purges, "purges"));
        this.emails = Objects.requireNonNull(emails, "emails");
        String domain = emailDomain == null || emailDomain.isBlank() ? DEFAULT_EMAIL_DOMAIN : emailDomain.strip();
        this.emailDomain = domain.startsWith("@") ? domain.substring(1) : domain;
        this.defaultAdmins = defaultAdmins == null ? List.of()
                : defaultAdmins.stream().filter(Objects::nonNull).toList();
    }

    /**
     * Who shapes the default namespace. **Empty means it is open to every
     * signed-in user, who is an admin there** — that is what a single-user
     * installation, the dev mode and every test rely on. Naming anybody closes
     * it: from then on the default namespace is a namespace like any other, and
     * somebody the installation has not listed is in nothing (see
     * {@link #role}).
     */
    public List<Email> defaultAdmins() {
        return defaultAdmins;
    }

    /** Whether the default namespace takes every signed-in user, because nobody was named. */
    public boolean defaultIsOpen() {
        return defaultAdmins.isEmpty();
    }

    /** The domain a bare name gets when the installation configures none. */
    public static final String DEFAULT_EMAIL_DOMAIN = "local";

    /** What a bare name becomes an address with here. */
    public String emailDomain() {
        return emailDomain;
    }

    /** The namespace every user may work in without being invited. */
    public Namespace defaultNamespace() {
        return defaultNamespace;
    }

    /**
     * The default namespace's record, created on first use — with the admins the
     * installation named, if it named any.
     */
    public NamespaceDefinition defaultDefinition() {
        return namespaces.findById(defaultNamespace).orElseGet(() -> {
            NamespaceDefinition created = NamespaceDefinition.create(
                    defaultNamespace, null, defaultAdmins, Instant.now(clock));
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

    /**
     * Who {@code user} is to a namespace: their id, and the address they are listed
     * under. An account whose provider gives no address — a single-user
     * installation with authentication off, for instance — is listed under its id
     * at the installation's domain, so that it can be in a namespace at all.
     */
    public Actor actor(UserId user) {
        Objects.requireNonNull(user, "user");
        Email address = emails.emailOf(user)
                .orElseGet(() -> Email.qualified(user.value(), emailDomain));
        return Actor.of(user, address);
    }

    /** {@code raw} as an address here: a bare name gets the installation's domain. */
    public Email address(String raw) {
        return Email.qualified(raw, emailDomain);
    }

    /**
     * The namespaces {@code user} may work in: the default one first when it is
     * open or lists them, then the rest of their memberships. Empty means this
     * installation has not given them anywhere to work — nobody is put anywhere
     * by signing in.
     */
    public List<NamespaceDefinition> forUser(UserId user) {
        List<NamespaceDefinition> out = new ArrayList<>();
        if (canAccess(user, defaultNamespace)) out.add(defaultDefinition());
        for (NamespaceDefinition ns : namespaces.findFor(actor(user))) {
            if (!ns.id().equals(defaultNamespace)) out.add(ns);
        }
        return out;
    }

    /**
     * The namespaces {@code user} may work in <em>under {@code brand}</em>: the
     * ones that belong to it. A brand's namespace carries its own id, and so
     * does what somebody created while working under it; {@code null} is the
     * installation itself, where the namespaces of no brand live.
     */
    public List<NamespaceDefinition> forUser(UserId user, Namespace brand) {
        return forUser(user).stream().filter(ns -> ns.belongsTo(brand)).toList();
    }

    /** Whether {@code user} may work in {@code id}: the default namespace, or one that lists them. */
    public boolean canAccess(UserId user, Namespace id) {
        return role(user, id).isPresent();
    }

    /**
     * What {@code user} may do in {@code id}, or empty when they are not in it.
     * The default namespace makes every signed-in user an admin: it is the
     * installation's own, and it is where a single-user install works.
     */
    public Optional<NamespaceRole> role(UserId user, Namespace id) {
        if (defaultNamespace.equals(id) && defaultIsOpen()) return Optional.of(NamespaceRole.ADMIN);
        Actor who = actor(user);
        return find(id).flatMap(ns -> ns.role(who));
    }

    /** Whether {@code user} shapes {@code id} — creates and changes what it holds, and who is in it. */
    public boolean isAdmin(UserId user, Namespace id) {
        return role(user, id).filter(NamespaceRole.ADMIN::equals).isPresent();
    }

    /**
     * Creates an empty namespace, with {@code creator} as its only admin.
     *
     * @throws IllegalArgumentException when the id is malformed or taken
     */
    public NamespaceDefinition create(String id, String displayName, UserId creator) {
        return create(id, displayName, creator, null);
    }

    /** The same, belonging to {@code brand} — what somebody working under a brand creates is the brand's. */
    public NamespaceDefinition create(String id, String displayName, UserId creator, Namespace brand) {
        Objects.requireNonNull(creator, "creator");
        return insert(id, displayName, List.of(actor(creator).email()), brand);
    }

    /**
     * Creates an empty namespace whose admins are given rather than derived from
     * whoever is signing in — a brand's namespace from configuration, say. The
     * first of them is its creator, the one who may delete it.
     *
     * @throws IllegalArgumentException when the id is malformed or taken, or no admin is given
     */
    public NamespaceDefinition create(String id, String displayName, List<Email> admins) {
        return create(id, displayName, admins, null);
    }

    /** The same, belonging to {@code brand} — a brand's own namespace carries its own id. */
    public NamespaceDefinition create(String id, String displayName, List<Email> admins, Namespace brand) {
        if (admins == null || admins.isEmpty()) {
            throw new IllegalArgumentException("A namespace needs at least one admin");
        }
        return insert(id, displayName, admins, brand);
    }

    private NamespaceDefinition insert(String id, String displayName, List<Email> admins, Namespace brand) {
        String value = id == null ? "" : id.strip();
        if (!ID.matcher(value).matches()) {
            throw new IllegalArgumentException("A namespace id is 1–64 lower-case letters, digits, '-' or '_', got '" + id + "'");
        }
        if (RESERVED.contains(value)) {
            throw new IllegalArgumentException("'" + value + "' is reserved; pick another id");
        }
        Namespace namespace = new Namespace(value);
        if (defaultNamespace.equals(namespace) || namespaces.findById(namespace).isPresent()) {
            throw new IllegalArgumentException("Namespace '" + value + "' already exists");
        }
        String label = displayName == null || displayName.isBlank() ? null : displayName.strip();
        NamespaceDefinition created = NamespaceDefinition.create(namespace, label, admins, Instant.now(clock), brand);
        if (created.admins().isEmpty()) {
            throw new IllegalArgumentException("A namespace needs at least one admin");
        }
        if (!namespaces.insert(created)) {
            throw new IllegalArgumentException("Namespace '" + value + "' already exists");
        }
        return created;
    }

    /**
     * The namespace {@code id}, created with {@code admins} when it does not
     * exist yet — what an installation's own namespaces are brought into being
     * with: a brand's from configuration, somebody's personal one on their first
     * request. Idempotent, and it never changes a namespace that is already
     * there: who is in it afterwards is the business of its admins, not of a
     * configuration file that somebody edited later.
     *
     * @return the namespace, and whether this call is what created it
     */
    public Created ensure(String id, String displayName, List<Email> admins) {
        return ensure(id, displayName, admins, null);
    }

    /** The same, with the brand the new namespace would belong to. */
    public Created ensure(String id, String displayName, List<Email> admins, Namespace brand) {
        Namespace namespace = new Namespace(id == null ? "" : id.strip());
        Optional<NamespaceDefinition> existing = find(namespace);
        if (existing.isPresent()) return new Created(existing.get(), false);
        try {
            return new Created(create(id, displayName, admins, brand), true);
        } catch (IllegalArgumentException e) {
            // Somebody else inserted it between the two calls — two requests of
            // the same brand arriving together. Theirs is as good as ours.
            return new Created(find(namespace).orElseThrow(() -> e), false);
        }
    }

    /** A namespace and whether the call that asked for it is what brought it into being. */
    public record Created(NamespaceDefinition namespace, boolean fresh) {
    }

    /**
     * Puts {@code entry} — an e-mail address, of somebody who need not have signed
     * in yet — into {@code id} in {@code role}. Admins invite.
     *
     * @throws IllegalArgumentException when the namespace does not exist, is the open default one,
     *                                  or {@code inviter} does not shape it
     */
    public NamespaceDefinition invite(Namespace id, UserId inviter, Email entry, NamespaceRole role) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(role, "role");
        synchronized (lockFor(id)) {
            NamespaceDefinition ns = adminOnly(id, inviter, "invite into");
            NamespaceDefinition updated = ns.with(entry, role);
            if (updated != ns) namespaces.save(updated);
            return updated;
        }
    }

    /** Makes {@code entry} an admin of {@code id}. Admins promote. */
    public NamespaceDefinition promote(Namespace id, UserId actor, Email entry) {
        Objects.requireNonNull(entry, "entry");
        synchronized (lockFor(id)) {
            NamespaceDefinition ns = adminOnly(id, actor, "promote in");
            NamespaceDefinition updated = ns.withAdmin(entry);
            if (updated != ns) namespaces.save(updated);
            return updated;
        }
    }

    /** Makes {@code entry} a user of {@code id} again; the creator stays an admin. */
    public NamespaceDefinition demote(Namespace id, UserId actor, Email entry) {
        Objects.requireNonNull(entry, "entry");
        synchronized (lockFor(id)) {
            NamespaceDefinition ns = adminOnly(id, actor, "demote in");
            NamespaceDefinition updated = ns.demote(entry);
            if (updated != ns) namespaces.save(updated);
            return updated;
        }
    }

    /**
     * Removes {@code entry} from {@code id}. An admin removes anyone, a user
     * themselves; the creator cannot be removed at all.
     */
    public NamespaceDefinition removeMember(Namespace id, UserId actor, Email entry) {
        Objects.requireNonNull(entry, "entry");
        synchronized (lockFor(id)) {
            NamespaceDefinition ns = memberOnly(id, actor, "change");
            Actor who = actor(actor);
            if (!ns.isAdmin(who) && !who.matches(entry)) {
                throw new IllegalArgumentException("Only an admin of '" + id.value() + "' removes other members");
            }
            NamespaceDefinition updated = ns.without(entry);
            if (updated != ns) namespaces.save(updated);
            return updated;
        }
    }

    /** {@code actor} leaves {@code id}; the creator cannot leave, only delete. */
    public NamespaceDefinition leave(Namespace id, UserId actor) {
        synchronized (lockFor(id)) {
            NamespaceDefinition ns = memberOnly(id, actor, "leave");
            Email mine = ns.entryOf(actor(actor)).orElseThrow(
                    () -> new IllegalArgumentException("'" + actor.value() + "' is not a member of '" + id + "'"));
            NamespaceDefinition updated = ns.without(mine);
            if (updated != ns) namespaces.save(updated);
            return updated;
        }
    }

    /**
     * Deletes {@code id} with everything in it. Only its creator may — an admin
     * they promoted shapes the namespace but does not throw it away; the default
     * namespace cannot be deleted at all, since it is where the installation
     * itself works.
     *
     * @throws IllegalArgumentException when the namespace does not exist, is the default one,
     *                                  or {@code actor} did not create it
     * @throws IllegalStateException    when a store could not purge its data — the record stays, try again
     */
    public void delete(Namespace id, UserId actor) {
        // Under the namespace's lock like every other write: a variable or member change that
        // read the record before would otherwise save it back after the record was dropped,
        // and the namespace would return. A write waiting here reads again and finds nothing.
        if (defaultNamespace.equals(id)) {
            throw new IllegalArgumentException("The default namespace '" + id + "' belongs to the installation"
                    + " and cannot be deleted");
        }
        synchronized (lockFor(id)) {
            NamespaceDefinition ns = memberOnly(id, actor, "delete");
            if (!ns.isCreator(actor(actor))) {
                throw new IllegalArgumentException("Only the creator deletes '" + id.value() + "'");
            }
            for (NamespacePurge purge : purges) {
                try {
                    purge.purge(id);
                } catch (RuntimeException e) {
                    // A purge may have closed what the routed adapters hold; the retry and the
                    // namespace, still there, get fresh ones.
                    NamespaceRouted.evictEverywhere(id);
                    throw new IllegalStateException("Could not remove the data of '" + id + "': " + e.getMessage(), e);
                }
            }
            namespaces.deleteById(id);
            NamespaceRouted.evictEverywhere(id);
        }
    }

    public NamespaceDefinition rename(Namespace id, UserId actor, String displayName) {
        synchronized (lockFor(id)) {
            NamespaceDefinition ns = adminOnly(id, actor, "rename");
            NamespaceDefinition updated = ns.withDisplayName(displayName == null || displayName.isBlank() ? null : displayName.strip());
            namespaces.save(updated);
            return updated;
        }
    }

    /**
     * Replaces the variables of {@code id} with exactly {@code environment} —
     * every name with a value; the caller has already merged an edit into the
     * full set. Admins set them: they are the namespace's shared secrets, and
     * whoever shapes the namespace works with them anyway. Values go to the
     * repository as given; an encrypting repository decorator makes them
     * {@code enc:} at rest.
     */
    public NamespaceDefinition setEnvironment(Namespace id, UserId actor, Map<String, String> environment) {
        EnvVarResolver.requireValid(environment);
        synchronized (lockFor(id)) {
            NamespaceDefinition updated = variablesOf(id, actor).withEnvironment(environment);
            namespaces.save(updated);
            return updated;
        }
    }

    /** Adds the variable {@code name}, or replaces its value; admins only, merged under the namespace's lock. */
    public NamespaceDefinition putVariable(Namespace id, UserId actor, String name, String value) {
        EnvVarResolver.requireValid(name, value);
        synchronized (lockFor(id)) {
            NamespaceDefinition ns = variablesOf(id, actor);
            Map<String, String> merged = new LinkedHashMap<>(ns.environment());
            merged.put(name, value);
            NamespaceDefinition updated = ns.withEnvironment(merged);
            namespaces.save(updated);
            return updated;
        }
    }

    /**
     * Removes the variable {@code name}. Admins only, and the right to remove is
     * checked before the name is looked at, so nobody learns which variables a
     * namespace holds by asking to remove them.
     *
     * @return the namespace afterwards, empty when it had no variable of that name
     */
    public Optional<NamespaceDefinition> removeVariable(Namespace id, UserId actor, String name) {
        synchronized (lockFor(id)) {
            NamespaceDefinition ns = variablesOf(id, actor);
            Map<String, String> merged = new LinkedHashMap<>(ns.environment());
            if (merged.remove(name) == null) return Optional.empty();
            NamespaceDefinition updated = ns.withEnvironment(merged);
            namespaces.save(updated);
            return Optional.of(updated);
        }
    }

    /**
     * The namespace, when {@code actor} may set its variables. The default
     * namespace is refused: nobody created it and everyone works there, so it has
     * no variables of its own and a {@code ${VAR}} there means the server's
     * environment.
     */
    private NamespaceDefinition variablesOf(Namespace id, UserId actor) {
        if (defaultNamespace.equals(id) && defaultIsOpen()) {
            throw new IllegalArgumentException("The default namespace '" + id + "' has no variables of its own"
                    + " — everyone works there, so set them in the server's environment");
        }
        return adminOnly(id, actor, "set the variables of");
    }

    /**
     * Membership, name and variable changes are read-modify-write on one record, and deleting drops it:
     * one at a time per namespace, in this process.
     */
    private Object lockFor(Namespace id) {
        return locks.computeIfAbsent(id, n -> new Object());
    }

    /** The namespace, when {@code actor} shapes it. */
    private NamespaceDefinition adminOnly(Namespace id, UserId actor, String verb) {
        NamespaceDefinition ns = memberOnly(id, actor, verb);
        if (!ns.isAdmin(actor(actor))) {
            throw new IllegalArgumentException("Only an admin of '" + id.value() + "' may " + verb + " it");
        }
        return ns;
    }

    private NamespaceDefinition memberOnly(Namespace id, UserId actor, String verb) {
        Objects.requireNonNull(actor, "actor");
        if (defaultNamespace.equals(id) && defaultIsOpen()) {
            throw new IllegalArgumentException("The default namespace '" + id + "' is open to everyone; there is nothing to " + verb);
        }
        NamespaceDefinition ns = find(id)
                .orElseThrow(() -> new IllegalArgumentException("No namespace '" + id + "'"));
        if (!ns.isMember(actor(actor))) {
            throw new IllegalArgumentException("'" + actor.value() + "' is not a member of '" + id + "'");
        }
        return ns;
    }
}
