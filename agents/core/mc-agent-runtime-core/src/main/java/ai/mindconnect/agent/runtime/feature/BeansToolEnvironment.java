package ai.mindconnect.agent.runtime.feature;

import ai.mindconnect.agent.tool.ToolEnvironment;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The tool environment as a view of the runtime's registry: a tool provider
 * asking for a service gets the bean a feature registered, decorated as the
 * core uses it, and a string setting is a {@link FeatureContext#property
 * property}. Nothing is copied — a bean a later feature adds is visible to
 * the tools without anyone maintaining a second list.
 */
public class BeansToolEnvironment implements ToolEnvironment {

    private final RuntimeBeans beans;
    private final Supplier<Map<String, String>> properties;

    public BeansToolEnvironment(RuntimeBeans beans, Supplier<Map<String, String>> properties) {
        this.beans = beans;
        this.properties = properties;
    }

    @Override
    public <T> Optional<T> get(Class<T> type) {
        return beans.find(type);
    }

    @Override
    public Optional<String> getString(String key) {
        return Optional.ofNullable(properties.get().get(key)).filter(s -> !s.isBlank());
    }
}
