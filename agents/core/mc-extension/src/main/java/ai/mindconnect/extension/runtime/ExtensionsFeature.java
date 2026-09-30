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
        // Wraps the bean the tools feature registers — the operator's settings
        // already laid over the classpath's offer — so this is the outermost
        // layer: a namespace's decision is the last word on a tool.
        ctx.decorate(ToolRegistry.class, registry -> new ExtensionToolRegistry(registry, extensions));
    }
}
