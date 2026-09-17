package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.agent.Email;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.adminui.branding.Branding;
import ai.mindconnect.adminui.branding.BrandingProperties;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What happens the first time somebody arrives: the brand's namespace is
 * brought into being, and whoever is listed somewhere gets a namespace of
 * their own to work in.
 *
 * <p>There is no login event in this application — no success handler, no
 * event — so this runs on the first request that carries an authenticated
 * user, once per session (see {@code NamespaceOnboardingFilter}).
 *
 * <p><strong>Nobody is put into a namespace by signing in.</strong> Arriving
 * under a brand's host does not make anybody a member of it: an account this
 * installation has not listed anywhere is turned away with
 * {@link Outcome#NO_NAMESPACE}, and somebody has to invite them. A host is not
 * a permission — otherwise everyone with an account in the realm and the
 * address of the page would be inside.
 *
 * <p>What it does do:
 *
 * <ol>
 *   <li>The brand of the request's host names a namespace and its admins
 *       ({@code mindconnect.branding.switch.<brand>.namespace}) — that
 *       namespace is created when it does not exist yet, with those admins.
 *       An existing one is never changed from configuration.</li>
 *   <li>Somebody who is in at least one namespace also gets their own, empty
 *       one, with them as its admin: a place of their own next to the shared
 *       work. Somebody who is in none gets nothing — their own namespace would
 *       be a membership nobody granted.</li>
 *   <li>The namespace they land in: the brand's when they are in it and have
 *       not chosen anywhere yet, so that the first sign-in under a brand opens
 *       that brand's work rather than an empty namespace.</li>
 * </ol>
 */
@Service
public class NamespaceOnboarding {

    private static final Logger log = LoggerFactory.getLogger(NamespaceOnboarding.class);

    /** What the request may do afterwards. */
    public enum Outcome {
        /** The user has somewhere to work; the request goes on. */
        ALLOWED,
        /** The user is in no namespace: nothing to show them but the way out. */
        NO_NAMESPACE
    }

    private final NamespaceService namespaces;
    private final UserService users;
    private final BrandingProperties branding;

    public NamespaceOnboarding(NamespaceService namespaces, UserService users, BrandingProperties branding) {
        this.namespaces = Objects.requireNonNull(namespaces, "namespaces");
        this.users = Objects.requireNonNull(users, "users");
        this.branding = Objects.requireNonNull(branding, "branding");
    }

    /**
     * Prepares what {@code user} needs to work under {@code host}, and answers
     * whether there is anything for them here.
     */
    public Outcome onboard(UserId user, String host) {
        Objects.requireNonNull(user, "user");
        Namespace brand = brandNamespace(host);
        List<NamespaceDefinition> mine = namespaces.forUser(user);
        if (mine.isEmpty()) {
            log.info("{} is in no namespace — nobody has invited them", user.value());
            return Outcome.NO_NAMESPACE;
        }
        // The open default namespace is not a membership: it is what a
        // single-user installation works in, and nobody put anybody there.
        boolean listed = mine.stream().anyMatch(ns -> !isOpenDefault(ns));
        if (listed) personalNamespace(user);
        land(user, brand, mine);
        return Outcome.ALLOWED;
    }

    private boolean isOpenDefault(NamespaceDefinition ns) {
        return ns.id().equals(namespaces.defaultNamespace()) && namespaces.defaultIsOpen();
    }

    /**
     * The namespace of the brand this host wears, created when it is missing.
     * Null when the host wears no brand, or its brand brings no namespace.
     */
    private Namespace brandNamespace(String host) {
        Branding brand = branding.resolve(host);
        if (!brand.hasNamespace()) return null;
        NamespaceService.Created created = namespaces.ensure(brand.namespace(), brand.title(), brand.namespaceAdmins());
        if (created.fresh()) {
            log.info("Namespace '{}' created for the brand on host '{}', admins {}",
                    created.namespace().id().value(), host, brand.namespaceAdmins());
        }
        return created.namespace().id();
    }

    /**
     * A namespace of their own for somebody who already works somewhere: the id
     * from their user id, their address as its admin. A name that is taken — by
     * a brand, by another installation-wide namespace — is left alone rather
     * than fought over; then they simply have no personal one.
     */
    private void personalNamespace(UserId user) {
        String id = personalId(user);
        if (id == null) return;
        Email mine = namespaces.actor(user).email();
        NamespaceService.Created personal = namespaces.ensure(id, users.find(user)
                .map(stored -> stored.label()).orElse(user.value()), List.of(mine));
        if (personal.fresh()) {
            log.info("Namespace '{}' created for {}", id, user.value());
        }
    }

    /**
     * Where they start: the brand's namespace, when they are in it and have not
     * chosen one yet. A choice they made before stays theirs — this is a first
     * landing point, not a redirection on every sign-in.
     */
    private void land(UserId user, Namespace brand, List<NamespaceDefinition> mine) {
        if (brand == null) return;
        if (users.activeNamespace(user).isPresent()) return;
        if (mine.stream().noneMatch(ns -> ns.id().equals(brand))) return;
        users.selectNamespace(user, brand);
    }

    /**
     * A user id as a namespace id: lower-case, anything else an underscore, and
     * nothing that a namespace may not be called. Null when what is left could
     * not be one.
     */
    static String personalId(UserId user) {
        String raw = user.value().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        while (raw.startsWith("_") || raw.startsWith("-")) {
            raw = raw.substring(1);
        }
        String id = raw.length() > 64 ? raw.substring(0, 64) : raw;
        if (id.isEmpty() || NamespaceService.RESERVED.contains(id) || !NamespaceService.ID.matcher(id).matches()) {
            return null;
        }
        return id;
    }
}
