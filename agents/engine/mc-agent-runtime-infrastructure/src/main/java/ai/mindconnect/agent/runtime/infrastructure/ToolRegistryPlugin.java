package ai.mindconnect.agent.runtime.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionEffect;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionRule;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionRuleSource;
import ai.mindconnect.agent.tool.ConfiguredToolRegistry;
import ai.mindconnect.agent.tool.OverlayToolRegistry;
import ai.mindconnect.agent.tool.SpiToolRegistry;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.tool.ToolRegistryRef;
import ai.mindconnect.agent.tool.ToolRepository;
import ai.mindconnect.agent.tool.UserToolRoster;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * The tools of an installation: every tool a module on the classpath
 * offers, less the ones the operator disabled, with the operator's settings
 * laid over them — and their way into the runtime, which resolves a tool an
 * agent binds through this registry.
 *
 * <p>A tool asks its environment for what it works with: a service of the
 * runtime, a service of the host, a setting.
 */
public final class ToolRegistryPlugin implements Plugin {

    /**
     * A layer over the registry, for a plugin that has a say in which tools
     * there are: every contributed one wraps what the ones before it left,
     * so the last has the last word.
     */
    @FunctionalInterface
    public interface Layer {

        ToolRegistry over(ToolRegistry tools);
    }

    private final Map<String, String> settings;
    private final String disabledTools;
    private final Function<Class<?>, Optional<?>> hostServices;
    private SpiToolRegistry tools;
    private PluginRegistry installedIn;

    /**
     * @param settings      what a tool reads by key: a base directory, an API key
     * @param disabledTools the names the installation switches off, comma-separated; {@code null} for none
     * @param hostServices  what the host offers beside the runtime's own services; empty for a type it has not
     */
    public ToolRegistryPlugin(Map<String, String> settings, String disabledTools,
                              Function<Class<?>, Optional<?>> hostServices) {
        this.settings = Map.copyOf(settings);
        this.disabledTools = disabledTools;
        this.hostServices = hostServices;
    }

    @Override
    public String name() {
        return "tool-registry";
    }

    @Override
    public void install(PluginRegistry registry) {
        installedIn = registry;
        registry.provide(ToolEnvironment.class, () -> new ToolEnvironment() {
            @Override
            public <T> Optional<T> get(Class<T> type) {
                Optional<T> own = registry.find(type);
                return own.isPresent() ? own : hostServices.apply(type).map(type::cast);
            }

            @Override
            public Optional<String> getString(String key) {
                return Optional.ofNullable(settings.get(key)).filter(value -> !value.isBlank());
            }
        });
        registry.provide(ToolRegistryPlugin.class, () -> this);
        registry.provide(ToolRegistryRef.class, ToolRegistryRef::new);
        registry.provide(ToolRegistry.class, () -> {
            // Deferred: the tools bind their providers on warmUp, once the host is up.
            tools = SpiToolRegistry.deferred(registry.get(ToolEnvironment.class));
            ToolRegistry offered = ConfiguredToolRegistry.of(tools, disabledTools);
            ToolRegistry configured = registry.find(ToolRepository.class)
                    .<ToolRegistry>map(operator -> new OverlayToolRegistry(offered, operator))
                    .orElse(offered);
            ToolRegistry effective = configured;
            for (Layer layer : registry.all(Layer.class)) {
                effective = layer.over(effective);
            }
            ToolRegistry layered = effective;
            registry.get(ToolRegistryRef.class).set(layered);
            return layered;
        });
        // Whoever keeps tools in their own account: a service of the runtime or of the host, or nobody.
        RegistryToolResolver resolver = new RegistryToolResolver(registry.supplier(ToolRegistry.class),
                () -> registry.get(ToolEnvironment.class).get(UserToolRoster.class).orElseGet(UserToolRoster::none));
        registry.contribute(ToolResolver.class, resolver);
        // An offer starts from what the agent names; what its user brought along is added here.
        registry.after(RuntimeHook.TOOL_OFFER, (context, tools) -> {
            List<ToolDefinition> offered = new ArrayList<>(tools);
            resolver.added(context.agent(), context.session()).stream()
                    .filter(added -> offered.stream().noneMatch(tool -> tool.name().equals(added.name())))
                    .forEach(offered::add);
            return offered;
        });
        // A tool is switched on by the agent naming it; the registry already leaves out what is disabled.
        // A tool the session's user wants to be asked about asks, whatever the agent's entry says.
        registry.contribute(PermissionRuleSource.class, session -> {
            List<PermissionRule> rules = new ArrayList<>();
            if (session.agent() != null) {
                resolver.asking(session.agent(), session)
                        .forEach(tool -> rules.add(new PermissionRule(tool, PermissionEffect.ASK)));
            }
            rules.add(new PermissionRule(PermissionRule.ANY, PermissionEffect.ALLOW));
            return rules;
        });
    }

    /** Binds the tools to their providers; call it once the host and its services are up. */
    public void warmUp() {
        installedIn.get(ToolRegistry.class);
        tools.warmUp();
    }
}
