package ai.mindconnect.plugin;

import java.util.List;
import java.util.Set;

/**
 * One installable capability: trusted code the operator installs. A plugin
 * owns its code and configuration and steps into its host through the
 * services it provides, the contributions it makes and the hooks it
 * registers.
 *
 * <p>A plugin is the one class of its module that knows the registry. It
 * takes what the module needs out of it and builds the module's classes
 * with their constructors — those classes get what they depend on handed
 * in and never see a registry.
 */
public interface Plugin {

    /** Stable name; installing a second plugin under the same name replaces the first. */
    String name();

    /** Plugins that must be installed before this one. A missing one is an error, not an auto-install. */
    default Set<Class<? extends Plugin>> dependsOn() {
        return Set.of();
    }

    /**
     * The services this plugin cannot work without. Whoever provides them
     * may be installed before or after it; that each has a provider is
     * checked once, when the host starts — a missing service is an error
     * while starting, not in the middle of the first request.
     */
    default Set<Class<?>> requires() {
        return Set.of();
    }

    /**
     * The settings this plugin reads. Declared so that the host knows which
     * exist: a required one that is not set, or one set to text that cannot
     * be read, is an error while starting, and a key no plugin declared is
     * named as unknown.
     */
    default List<Setting<?>> settings() {
        return List.of();
    }

    void install(PluginRegistry registry);

    /** Called once every plugin is installed, in installation order. */
    default void start() {
    }

    /** Called when the host closes, in reverse installation order. */
    default void close() {
    }
}
