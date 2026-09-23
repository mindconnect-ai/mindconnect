package ai.mindconnect.extension.runtime;

import ai.mindconnect.agent.runtime.feature.ConfigurableFeature;
import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.RuntimeFeature;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.extension.service.ExtensionService;

import java.util.Objects;
import java.util.Set;

/**
 * What the extensions add to the runtime: a namespace's decisions reach the
 * tool registry, so a switched-off extension's tools are gone for that
 * namespace. Installed by the host that has an {@link ExtensionService} —
 * the Spring auto-configuration registers it as a bean — never from the
 * classpath, because it needs the service.
 */
public final class ExtensionsFeature extends ConfigurableFeature {

    private final ExtensionService extensions;

    public ExtensionsFeature(ExtensionService extensions) {
        this.extensions = Objects.requireNonNull(extensions, "extensions");
    }

    @Override
    public String name() {
        return "extensions";
    }

    @Override
    public Set<Class<? extends RuntimeFeature>> dependsOn() {
        return Set.of();
    }

    @Override
    protected void install(FeatureContext ctx) {
        // Beneath the operator's tool settings, which the tools feature lays over
        // its registry: decorators wrap in registration order, and this feature
        // registers before the tools feature has resolved anything.
        ctx.decorate(ToolRegistry.class, registry -> new ExtensionToolRegistry(registry, extensions));
    }
}
