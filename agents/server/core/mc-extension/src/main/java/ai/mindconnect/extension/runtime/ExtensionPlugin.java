package ai.mindconnect.extension.runtime;

import ai.mindconnect.agent.runtime.infrastructure.ToolRegistryPlugin;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.extension.service.ExtensionService;

import java.util.Objects;

/**
 * What the extensions add to the runtime: a namespace's decisions reach the
 * tool registry, so a switched-off extension's tools are gone for that
 * namespace. Installed by the host that has an {@link ExtensionService} —
 * the Spring auto-configuration registers it as a bean — never from the
 * classpath, because it needs the service.
 */
public final class ExtensionPlugin implements Plugin {

    private final ExtensionService extensions;

    public ExtensionPlugin(ExtensionService extensions) {
        this.extensions = Objects.requireNonNull(extensions, "extensions");
    }

    @Override
    public String name() {
        return "extensions";
    }

    @Override
    public void install(PluginRegistry registry) {
        // Over the operator's settings and the classpath's offer, so this is the outermost
        // layer: a namespace's decision is the last word on a tool.
        registry.contribute(ToolRegistryPlugin.Layer.class, tools -> new ExtensionToolRegistry(tools, extensions));
    }
}
