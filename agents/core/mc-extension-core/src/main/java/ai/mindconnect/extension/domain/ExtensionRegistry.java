package ai.mindconnect.extension.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The extensions the host found at start, checked against each other. Built
 * once, before anything is wired, and read by everybody who has to know what
 * an extension declared: the tool overlay, the menu, the Extensions screen.
 *
 * <p>Two manifests with one id, two extensions replacing the same bean, a
 * required extension that is not there — each is a {@link Problem}. The
 * registry keeps them rather than throwing, so the host can decide: refuse
 * to start (strict), or start and show them on the screen. The first of two
 * manifests with the same id is kept, the second becomes a problem.
 */
public final class ExtensionRegistry {

    /** Something wrong with what the classpath offers; {@code id} may be null when it is about a manifest that could not be read. */
    public record Problem(ExtensionId id, String message) {
        @Override
        public String toString() {
            return (id == null ? "" : id + ": ") + message;
        }
    }

    private final Map<ExtensionId, Extension> byId;
    private final List<Problem> problems;

    public ExtensionRegistry(List<Extension> found) {
        this(found, List.of());
    }

    /**
     * @param found       the manifests the loader could read, in classpath order
     * @param loadProblems what the loader could not read, one line each
     */
    public ExtensionRegistry(List<Extension> found, List<Problem> loadProblems) {
        Map<ExtensionId, Extension> map = new LinkedHashMap<>();
        List<Problem> issues = new ArrayList<>(loadProblems);
        for (Extension extension : found) {
            if (extension.manifest().runtime() != ExtensionRuntime.JAR) {
                // Reported, and not wired: what a remote manifest declares is for a server the host
                // talks to, not for the classpath it was found on.
                issues.add(new Problem(extension.id(), "runtime " + extension.manifest().runtime().wireName()
                        + " found on the classpath — only jar extensions load from there; ignored"));
                continue;
            }
            Extension previous = map.putIfAbsent(extension.id(), extension);
            if (previous != null) {
                issues.add(new Problem(extension.id(), "declared twice: " + previous.origin()
                        + " and " + extension.origin() + " — keeping the first"));
            }
        }
        Map<String, ExtensionId> replacedBy = new HashMap<>();
        for (Extension extension : map.values()) {
            ExtensionManifest manifest = extension.manifest();
            for (String seam : manifest.contributes().replaces()) {
                ExtensionId other = replacedBy.putIfAbsent(seam, manifest.id());
                if (other != null) {
                    issues.add(new Problem(manifest.id(), "replaces " + seam + ", which " + other
                            + " already replaces — one extension per seam"));
                }
            }
            for (ExtensionManifest.Requires.Dependency dependency : manifest.requires().extensions()) {
                if (!map.containsKey(dependency.id()) && !dependency.isOptional()) {
                    issues.add(new Problem(manifest.id(), "requires " + dependency.id() + ", which is not installed"));
                }
            }
            for (ExtensionManifest.Ui.Route route : manifest.contributes().ui().routes()) {
                if (!route.isOwnedBy(manifest.id())) {
                    issues.add(new Problem(manifest.id(), "route " + route.path() + " is not under /admin/"
                            + manifest.id() + "/ or /ext/" + manifest.id() + "/ — ignored"));
                }
            }
        }
        // Not Map.copyOf: that forgets the order, and the order is the classpath's — what the screen lists by.
        this.byId = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(map));
        this.problems = List.copyOf(issues);
    }

    public static ExtensionRegistry empty() {
        return new ExtensionRegistry(List.of());
    }

    /** Every extension found, in classpath order. */
    public List<Extension> all() {
        return List.copyOf(byId.values());
    }

    public Optional<Extension> find(ExtensionId id) {
        return Optional.ofNullable(byId.get(id));
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    public int size() {
        return byId.size();
    }

    /** An extension and the one of its routes that covers a path. */
    public record RouteMatch(Extension extension, ExtensionManifest.Ui.Route route) {
    }

    /**
     * The extension whose manifest names a route covering the path, and that
     * route — whatever the extension's state anywhere. Only routes an
     * extension may own count ({@link ExtensionManifest.Ui.Route#isOwnedBy});
     * the others were reported at start and are ignored.
     */
    public Optional<RouteMatch> routeFor(String requestPath) {
        for (Extension extension : byId.values()) {
            for (ExtensionManifest.Ui.Route route : extension.manifest().contributes().ui().routes()) {
                if (route.isOwnedBy(extension.id()) && route.covers(requestPath)) {
                    return Optional.of(new RouteMatch(extension, route));
                }
            }
        }
        return Optional.empty();
    }

    /** The extension whose manifest names a route covering the path, if any. */
    public Optional<Extension> routeOwner(String requestPath) {
        return routeFor(requestPath).map(RouteMatch::extension);
    }

    /** What is wrong with the set as a whole; empty when everything fits. */
    public List<Problem> problems() {
        return problems;
    }

    public boolean hasProblems() {
        return !problems.isEmpty();
    }
}
