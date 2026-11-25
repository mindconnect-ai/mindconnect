package ai.mindconnect.extension.runtime;

import ai.mindconnect.agent.runtime.infrastructure.ToolRegistryPlugin;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.extension.adapter.classpath.ClasspathManifests;
import ai.mindconnect.extension.domain.ExtensionRegistry;
import ai.mindconnect.extension.port.out.BrandActivationRepository;
import ai.mindconnect.extension.port.out.ExtensionActivationRepository;
import ai.mindconnect.extension.port.out.InstalledSeedRepository;
import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

import ai.mindconnect.extension.service.ExtensionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The extensions of an installation, in the runtime they feed: the manifests
 * on the classpath, a namespace's decisions and the {@link ExtensionService}
 * over both, and the layer that lets those decisions reach the tool registry,
 * so a switched-off extension's tools are gone for that namespace. The stores
 * live where the runtime is built, because the runtime asks the service while
 * it resolves tools; a subclass says where they are kept.
 *
 * <p>{@code strict} decides what a problem with a manifest means: {@code false}
 * logs it, {@code true} refuses to start — for an installation that wants
 * nothing on its classpath it has not been told about.
 */
public abstract class ExtensionPlugin implements Plugin {

    private static final Logger log = LoggerFactory.getLogger(ExtensionPlugin.class);

    /** The stores of one place; each is asked once, when the runtime first needs it. */
    protected interface Stores {

        /** A namespace's decisions, routed by the current scope. */
        ExtensionActivationRepository activations();

        /** Which bundled records a namespace got once, so that a deleted one stays deleted. */
        InstalledSeedRepository installedSeeds();

        /** The brands' decisions, installation-wide. */
        BrandActivationRepository brands();
    }

    private final boolean strict;
    private final Set<String> disabledByOperator;

    /**
     * @param strict             whether a problem with a manifest refuses the start
     * @param disabledByOperator extension ids the operator switched off everywhere
     */
    protected ExtensionPlugin(boolean strict, Set<String> disabledByOperator) {
        this.strict = strict;
        this.disabledByOperator = Set.copyOf(disabledByOperator);
    }

    @Override
    public String name() {
        return "extensions";
    }

    /** The stores, made once the plugins are installed: what they need is in {@code registry} by then. */
    protected abstract Stores stores(PluginRegistry registry);

    @Override
    public void install(PluginRegistry registry) {
        // The loader of the host that installs this, not of the thread that first asks.
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Supplier<Stores> stores = new Supplier<>() {
            private Stores made;

            @Override
            public synchronized Stores get() {
                if (made == null) {
                    made = stores(registry);
                }
                return made;
            }
        };
        registry.provide(ExtensionRegistry.class,
                () -> checked(ClasspathManifests.load(classLoader, registry.get(JsonMapperFactory.class))));
        registry.provide(ExtensionActivationRepository.class, () -> stores.get().activations());
        registry.provide(InstalledSeedRepository.class, () -> stores.get().installedSeeds());
        registry.provide(BrandActivationRepository.class, () -> stores.get().brands());
        // Told the brand of the namespace at hand per call; without a namespace service there are no brands.
        registry.provide(ExtensionService.class, () -> new ExtensionService(registry.get(ExtensionRegistry.class),
                registry.get(ExtensionActivationRepository.class), registry.get(BrandActivationRepository.class),
                () -> currentBrand(registry), disabledByOperator));
        // Over the operator's settings and the classpath's offer, so this is the outermost
        // layer: a namespace's decision is the last word on a tool.
        registry.contribute(ToolRegistryPlugin.Layer.class,
                tools -> new ExtensionToolRegistry(tools, registry.get(ExtensionService.class)));
    }

    private static Optional<String> currentBrand(PluginRegistry registry) {
        Optional<NamespaceService> namespaces = registry.find(NamespaceService.class);
        if (namespaces.isEmpty()) return Optional.empty();
        return namespaces.get().find(registry.get(ScopeSupplier.class).namespace())
                .map(NamespaceDefinition::brand).map(Namespace::value);
    }

    private ExtensionRegistry checked(ExtensionRegistry manifests) {
        if (manifests.hasProblems()) {
            for (ExtensionRegistry.Problem problem : manifests.problems()) {
                log.warn("Extension problem: {}", problem);
            }
            if (strict) {
                throw new IllegalStateException("Refusing to start with " + manifests.problems().size()
                        + " extension problem(s) (mindconnect.extensions.strict=true): " + manifests.problems());
            }
        }
        log.info("Extensions: {} found on the classpath", manifests.size());
        return manifests;
    }
}
