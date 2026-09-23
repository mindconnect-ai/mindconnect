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
            if (manifest.runtime() != ExtensionRuntime.JAR) {
                issues.add(new Problem(manifest.id(), "runtime " + manifest.runtime().wireName()
                        + " found on the classpath — only jar extensions load from there"));
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

    /** The extension whose manifest names a route covering the path, if any — whatever its state anywhere. */
    public Optional<Extension> routeOwner(String requestPath) {
        for (Extension extension : byId.values()) {
            for (ExtensionManifest.Ui.Route route : extension.manifest().contributes().ui().routes()) {
                if (route.covers(requestPath)) return Optional.of(extension);
            }
        }
        return Optional.empty();
    }

    /** What is wrong with the set as a whole; empty when everything fits. */
    public List<Problem> problems() {
        return problems;
    }

    public boolean hasProblems() {
        return !problems.isEmpty();
    }
}
