package ai.mindconnect.extension.service;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.extension.domain.BrandActivation;
import ai.mindconnect.extension.domain.Extension;
import ai.mindconnect.extension.domain.ExtensionActivation;
import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.extension.domain.ExtensionManifest;
import ai.mindconnect.extension.domain.ExtensionRegistry;
import ai.mindconnect.extension.domain.NamePattern;
import ai.mindconnect.extension.port.out.BrandActivationRepository;
import ai.mindconnect.extension.port.out.ExtensionActivationRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What the host asks about extensions in the namespace at hand: which are
 * there, which are on, and what a switched-off one takes with it.
 *
 * <p>Three levels decide, in this order of precedence:
 * <ol>
 *   <li><b>the operator</b> — an extension named in
 *       {@code mindconnect.extensions.disabled} is off everywhere, and nobody
 *       below can switch it on;</li>
 *   <li><b>the brand</b>, when its decision is <em>locked</em> — holds in every
 *       namespace of the brand, whatever the namespace said;</li>
 *   <li><b>the namespace</b> — its own decision;</li>
 *   <li><b>the brand</b>, unlocked — what the brand's namespaces have unless
 *       they decided for themselves;</li>
 *   <li><b>the manifest</b> — {@code enabledByDefault}.</li>
 * </ol>
 * The namespace repository is the namespace's — routed above this service —
 * and the brand is looked up per call, so nothing here names either.
 *
 * <p>Switching is an admin's act; whether the caller is one is settled at the
 * door (the access interceptor of the admin UI), like for every other admin
 * screen. Deciding for a brand is done from the brand's own namespace.
 */
public class ExtensionService {

    /** Where the state of an extension in this namespace comes from. */
    public enum Origin {
        /** Off by {@code mindconnect.extensions.disabled}; nothing below can change it. */
        OPERATOR,
        /** The brand decided and locked it; the namespace cannot override. */
        BRAND_LOCKED,
        /** The namespace decided for itself. */
        NAMESPACE,
        /** The brand decided; the namespace could still override. */
        BRAND,
        /** Nobody decided; the manifest's default holds. */
        DEFAULT
    }

    /**
     * One extension as it stands in the namespace: the manifest, the
     * effective state and where it comes from, and the decisions on the
     * way there — the namespace's and the brand's, if any were taken.
     */
    public record Status(Extension extension, boolean enabled, Origin origin,
                         Optional<ExtensionActivation> decision, Optional<BrandActivation> brandDecision,
                         Optional<String> brand) {

        public Status {
            Objects.requireNonNull(extension, "extension");
            origin = origin == null ? Origin.DEFAULT : origin;
            decision = decision == null ? Optional.empty() : decision;
            brandDecision = brandDecision == null ? Optional.empty() : brandDecision;
            brand = brand == null ? Optional.empty() : brand;
        }

        /** A status without brand or operator: the namespace decided, or the manifest holds. */
        public Status(Extension extension, boolean enabled, Optional<ExtensionActivation> decision) {
            this(extension, enabled, decision != null && decision.isPresent() ? Origin.NAMESPACE : Origin.DEFAULT,
                    decision, Optional.empty(), Optional.empty());
        }

        public ExtensionId id() {
            return extension.id();
        }

        public ExtensionManifest manifest() {
            return extension.manifest();
        }

        /** Whether this is what the manifest says rather than what anybody decided. */
        public boolean isDefault() {
            return origin == Origin.DEFAULT;
        }

        /** Whether an admin of this namespace can change the state here — not against the operator or a locked brand. */
        public boolean canDecideHere() {
            return origin != Origin.OPERATOR && origin != Origin.BRAND_LOCKED;
        }
    }

    private final ExtensionRegistry registry;
    private final ExtensionActivationRepository activations;
    private final BrandActivationRepository brands;
    private final Supplier<Optional<String>> currentBrand;
    private final Set<String> disabledByOperator;

    /** A host without brands and without an operator's list. */
    public ExtensionService(ExtensionRegistry registry, ExtensionActivationRepository activations) {
        this(registry, activations, BrandActivationRepository.none(), Optional::empty, Set.of());
    }

    /**
     * @param registry           the manifests found at start
     * @param activations        the namespace's decisions, routed by the current scope
     * @param brands             the brands' decisions, installation-wide
     * @param currentBrand       the brand the current namespace belongs to, if any — asked per call
     * @param disabledByOperator extension ids the operator switched off everywhere
     */
    public ExtensionService(ExtensionRegistry registry, ExtensionActivationRepository activations,
                            BrandActivationRepository brands, Supplier<Optional<String>> currentBrand,
                            Set<String> disabledByOperator) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.activations = Objects.requireNonNull(activations, "activations");
        this.brands = Objects.requireNonNull(brands, "brands");
        this.currentBrand = Objects.requireNonNull(currentBrand, "currentBrand");
        this.disabledByOperator = disabledByOperator == null ? Set.of() : Set.copyOf(disabledByOperator);
    }

    public ExtensionRegistry registry() {
        return registry;
    }

    /** The brand the current namespace belongs to, if it belongs to one. */
    public Optional<String> currentBrand() {
        return currentBrand.get();
    }

    /** Every extension found, with its state in the current namespace. */
    public List<Status> list() {
        Optional<String> brand = currentBrand.get();
        List<Status> statuses = new ArrayList<>();
        for (Extension extension : registry.all()) {
            statuses.add(status(extension, brand));
        }
        return statuses;
    }

    public Optional<Status> find(ExtensionId id) {
        return registry.find(id).map(extension -> status(extension, currentBrand.get()));
    }

    private Status status(Extension extension, Optional<String> brand) {
        ExtensionId id = extension.id();
        Optional<ExtensionActivation> decision = activations.find(id);
        Optional<BrandActivation> brandDecision = brand.flatMap(b -> brands.find(b, id));
        boolean enabled;
        Origin origin;
        if (disabledByOperator.contains(id.value())) {
            enabled = false;
            origin = Origin.OPERATOR;
        } else if (brandDecision.isPresent() && brandDecision.get().locked()) {
            enabled = brandDecision.get().enabled();
            origin = Origin.BRAND_LOCKED;
        } else if (decision.isPresent()) {
            enabled = decision.get().enabled();
            origin = Origin.NAMESPACE;
        } else if (brandDecision.isPresent()) {
            enabled = brandDecision.get().enabled();
            origin = Origin.BRAND;
        } else {
            enabled = extension.manifest().isEnabledByDefault();
            origin = Origin.DEFAULT;
        }
        return new Status(extension, enabled, origin, decision, brandDecision, brand);
    }

    /** Whether the extension is on in the current namespace; an unknown id is off. */
    public boolean isEnabled(ExtensionId id) {
        return find(id).map(Status::enabled).orElse(false);
    }

    /** Switches the extension on for the current namespace. */
    public void enable(ExtensionId id, UserId by) {
        decide(id, true, by);
    }

    /** Switches the extension off for the current namespace. */
    public void disable(ExtensionId id, UserId by) {
        decide(id, false, by);
    }

    /** Forgets the namespace's decision: the extension is back to the brand's, or the manifest's. */
    public void reset(ExtensionId id) {
        require(id);
        activations.delete(id);
    }

    private void decide(ExtensionId id, boolean enabled, UserId by) {
        Status status = find(id).orElseThrow(() -> new IllegalArgumentException("No such extension: " + id));
        if (!status.canDecideHere()) {
            throw new IllegalStateException(status.origin() == Origin.OPERATOR
                    ? "'" + status.manifest().name() + "' is switched off by the operator (mindconnect.extensions.disabled)"
                    : "'" + status.manifest().name() + "' is decided and locked for the brand " + status.brand().orElse(""));
        }
        activations.save(ExtensionActivation.of(id, enabled, by));
    }

    /**
     * Decides for the whole brand the current namespace belongs to. {@code locked}
     * takes the namespaces' own say away.
     */
    public void decideForBrand(ExtensionId id, boolean enabled, boolean locked, UserId by) {
        require(id);
        brands.save(BrandActivation.of(requireBrand(), id, enabled, locked, by));
    }

    /** Locks or unlocks the brand's decision; without a decision, the manifest's default is what gets locked. */
    public void lockForBrand(ExtensionId id, boolean locked, UserId by) {
        Extension extension = require(id);
        String brand = requireBrand();
        BrandActivation current = brands.find(brand, id)
                .orElseGet(() -> BrandActivation.of(brand, id, extension.manifest().isEnabledByDefault(), false, by));
        brands.save(current.withLocked(locked, by));
    }

    /** Forgets the brand's decision. */
    public void resetBrand(ExtensionId id) {
        require(id);
        brands.delete(requireBrand(), id);
    }

    private String requireBrand() {
        return currentBrand.get()
                .orElseThrow(() -> new IllegalStateException("This namespace belongs to no brand"));
    }

    private Extension require(ExtensionId id) {
        return registry.find(id)
                .orElseThrow(() -> new IllegalArgumentException("No such extension: " + id));
    }

    /**
     * The extension whose manifest names a route covering the path, with its
     * state in the current namespace — for the host to wrap its pages in the
     * shell, and to refuse them where the extension is off.
     */
    public Optional<Status> routeOwner(String requestPath) {
        return registry.routeOwner(requestPath).map(extension -> status(extension, currentBrand.get()));
    }

    /**
     * Whether a tool of that name belongs to an extension that is off in the
     * current namespace — matched against the name patterns the manifests
     * declare ({@code acme_*}). A name no extension claims is nobody's to hide.
     */
    public boolean hidesTool(String toolName) {
        if (toolName == null) return false;
        for (Status status : list()) {
            if (status.enabled()) continue;
            for (String pattern : status.manifest().contributes().tools().names()) {
                if (matches(pattern, toolName)) return true;
            }
        }
        return false;
    }

    /**
     * Whether a sidebar entry of that id belongs to an extension that is off
     * in the current namespace — the ids the manifests declare under
     * {@code contributes.ui.menu}.
     */
    public boolean hidesMenuEntry(String entryId) {
        if (entryId == null) return false;
        for (Status status : list()) {
            if (status.enabled()) continue;
            for (ExtensionManifest.Ui.MenuEntry entry : status.manifest().contributes().ui().menu()) {
                if (entryId.equals(entry.id())) return true;
            }
        }
        return false;
    }

    /** See {@link NamePattern}. */
    static boolean matches(String pattern, String name) {
        return NamePattern.matches(pattern, name);
    }
}
