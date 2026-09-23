package ai.mindconnect.extension.adapter.classpath;

import ai.mindconnect.agent.runtime.feature.RuntimeFeature;
import ai.mindconnect.agent.tool.MultiToolProvider;
import ai.mindconnect.agent.tool.ToolFactory;
import ai.mindconnect.extension.domain.ExtensionManifest;
import ai.mindconnect.extension.domain.NamePattern;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Holds what a manifest declares against what the classpath actually has:
 * a provider class named under {@code contributes.tools.providers} or
 * {@code features} is either registered as a service, present but not
 * registered, or not there at all; a tool-name pattern matches so many
 * tools of the catalog, or none. The Extensions screen prints the verdict
 * beside each item, so a manifest that promises more than its jar delivers
 * is visible before anybody wonders why a tool is missing.
 *
 * <p>The provider classes are read once, at start, without instantiating
 * anything — the same rule as the audit. The tool names are asked for per
 * check, because the catalog fills up after start.
 */
public final class ContributionChecker {

    /** One declared item and whether the classpath has it; {@code detail} says how. */
    public record Check(String item, boolean found, String detail) {
    }

    /** The verdicts for one manifest. */
    public record Report(List<Check> providers, List<Check> features, List<Check> tools) {
        public static final Report EMPTY = new Report(List.of(), List.of(), List.of());

        public Report {
            providers = providers == null ? List.of() : List.copyOf(providers);
            features = features == null ? List.of() : List.copyOf(features);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }

        public boolean allFound() {
            return providers.stream().allMatch(Check::found) && features.stream().allMatch(Check::found)
                    && tools.stream().allMatch(Check::found);
        }
    }

    private final ClassLoader classLoader;
    private final Set<String> providerTypes;
    private final Set<String> featureTypes;
    private final Supplier<Set<String>> toolNames;

    /**
     * @param classLoader where the services are
     * @param toolNames   the names the tool catalog knows right now — asked per check
     */
    public ContributionChecker(ClassLoader classLoader, Supplier<Set<String>> toolNames) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.toolNames = Objects.requireNonNull(toolNames, "toolNames");
        Set<String> providers = new HashSet<>();
        providers.addAll(serviceTypes(ToolFactory.class));
        providers.addAll(serviceTypes(MultiToolProvider.class));
        this.providerTypes = Set.copyOf(providers);
        this.featureTypes = Set.copyOf(serviceTypes(RuntimeFeature.class));
    }

    private Set<String> serviceTypes(Class<?> spi) {
        Set<String> names = new HashSet<>();
        for (ServiceLoader.Provider<?> provider : ServiceLoader.load(spi, classLoader).stream().toList()) {
            names.add(provider.type().getName());
        }
        return names;
    }

    public Report check(ExtensionManifest manifest) {
        ExtensionManifest.Contributes brings = manifest.contributes();
        List<Check> providers = new ArrayList<>();
        for (String provider : brings.tools().providers()) {
            providers.add(classCheck(provider, providerTypes, "META-INF/services"));
        }
        List<Check> features = new ArrayList<>();
        for (String feature : brings.features()) {
            features.add(classCheck(feature, featureTypes, "META-INF/services"));
        }
        List<Check> tools = new ArrayList<>();
        if (!brings.tools().names().isEmpty()) {
            Set<String> known = toolNames.get();
            for (String pattern : brings.tools().names()) {
                long hits = known.stream().filter(name -> NamePattern.matches(pattern, name)).count();
                tools.add(hits > 0
                        ? new Check(pattern, true, hits == 1 ? "1 tool" : hits + " tools")
                        : new Check(pattern, false, "no tool of that name in the catalog"));
            }
        }
        return new Report(providers, features, tools);
    }

    private Check classCheck(String className, Set<String> registered, String where) {
        if (registered.contains(className)) return new Check(className, true, "registered");
        try {
            Class.forName(className, false, classLoader);
            return new Check(className, false, "on the classpath, but not registered in " + where);
        } catch (ClassNotFoundException | LinkageError e) {
            return new Check(className, false, "not on the classpath");
        }
    }

    /** The reports for several manifests, by extension id value. */
    public Map<String, Report> checkAll(List<ExtensionManifest> manifests) {
        Map<String, Report> reports = new java.util.LinkedHashMap<>();
        for (ExtensionManifest manifest : manifests) {
            reports.put(manifest.id().value(), check(manifest));
        }
        return reports;
    }
}
