package ai.mindconnect.agent.runtime.feature;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The central registry of a runtime's features. {@link #install} checks the
 * feature's dependencies against what is already installed and fails when
 * one is missing — at install time, so the stack trace points at the call
 * and the installation order reads as the dependency order.
 *
 * <p>A feature installed under a name already taken replaces the earlier one,
 * provided the new one is of the same class or a subclass — otherwise the
 * declarations of the features that depend on it would stop holding.
 */
public class FeatureRegistry implements Features {

    private final Map<String, RuntimeFeature> byName = new LinkedHashMap<>();

    public synchronized void install(RuntimeFeature feature) {
        for (Class<? extends RuntimeFeature> dependency : feature.dependsOn()) {
            if (find(dependency).isEmpty()) {
                throw new FeatureException(describe(feature) + " needs " + dependency.getSimpleName()
                        + ", which is not installed. Install it first.");
            }
        }
        RuntimeFeature previous = byName.get(feature.name());
        if (previous != null && !previous.getClass().isAssignableFrom(feature.getClass())) {
            throw new FeatureException(describe(feature) + " cannot replace " + describe(previous)
                    + ": a replacement must be of the same class or a subclass, so that features"
                    + " depending on " + previous.getClass().getSimpleName() + " still find it");
        }
        byName.put(feature.name(), feature);
    }

    /**
     * Installs the given features in dependency order, so that a set found on
     * the classpath needs no ordering of its own. A dependency neither in the
     * set nor already installed is reported like any other missing one.
     */
    public synchronized void installAll(Collection<? extends RuntimeFeature> features) {
        for (RuntimeFeature feature : sort(features)) {
            install(feature);
        }
    }

    /** Topological order of the given features by {@link RuntimeFeature#dependsOn()}; a cycle is an error. */
    public static List<RuntimeFeature> sort(Collection<? extends RuntimeFeature> features) {
        List<RuntimeFeature> ordered = new ArrayList<>();
        Set<RuntimeFeature> done = new LinkedHashSet<>();
        Set<RuntimeFeature> visiting = new LinkedHashSet<>();
        for (RuntimeFeature feature : features) {
            visit(feature, features, ordered, done, visiting);
        }
        return ordered;
    }

    private static void visit(RuntimeFeature feature, Collection<? extends RuntimeFeature> all,
                              List<RuntimeFeature> ordered, Set<RuntimeFeature> done, Set<RuntimeFeature> visiting) {
        if (done.contains(feature)) return;
        if (!visiting.add(feature)) {
            throw new FeatureException("Features depend on each other in a cycle: "
                    + visiting.stream().map(FeatureRegistry::describe).toList());
        }
        for (Class<? extends RuntimeFeature> dependency : feature.dependsOn()) {
            for (RuntimeFeature candidate : all) {
                if (dependency.isInstance(candidate)) {
                    visit(candidate, all, ordered, done, visiting);
                }
            }
        }
        visiting.remove(feature);
        done.add(feature);
        ordered.add(feature);
    }

    @Override
    public synchronized <F extends RuntimeFeature> F get(Class<F> type) {
        return find(type).orElseThrow(() -> new FeatureException(
                "Feature " + type.getSimpleName() + " is not installed"));
    }

    @Override
    public synchronized <F extends RuntimeFeature> Optional<F> find(Class<F> type) {
        for (RuntimeFeature feature : byName.values()) {
            if (type.isInstance(feature)) return Optional.of(type.cast(feature));
        }
        return Optional.empty();
    }

    @Override
    public synchronized Optional<RuntimeFeature> byName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    @Override
    public synchronized List<RuntimeFeature> all() {
        return List.copyOf(byName.values());
    }

    static String describe(RuntimeFeature feature) {
        return feature.getClass().getSimpleName() + " (\"" + feature.name() + "\")";
    }
}
