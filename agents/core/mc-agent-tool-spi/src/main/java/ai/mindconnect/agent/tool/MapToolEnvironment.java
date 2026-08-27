package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.tool.ToolEnvironment;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Mutable, builder-style {@link ToolEnvironment} backed by two maps: one for
 * typed services and one for named string values. Built once at runtime
 * start-up and handed to each {@link ai.mindconnect.agent.tool.ToolFactory}
 * via {@link ai.mindconnect.agent.tool.ToolFactory#bind(ToolEnvironment)}.
 */
public final class MapToolEnvironment implements ToolEnvironment {

    private final Map<Class<?>, Object> services;
    private final Map<String, String> strings;

    private MapToolEnvironment(Map<Class<?>, Object> services, Map<String, String> strings) {
        this.services = services;
        this.strings = strings;
    }

    public static Builder builder() { return new Builder(); }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(Class<T> type) {
        Object value = services.get(type);
        if (value != null) return Optional.of((T) value);
        for (Map.Entry<Class<?>, Object> e : services.entrySet()) {
            if (type.isInstance(e.getValue())) return Optional.of((T) e.getValue());
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> getString(String key) {
        String value = strings.get(key);
        return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value);
    }

    public static final class Builder {
        private final Map<Class<?>, Object> services = new LinkedHashMap<>();
        private final Map<String, String> strings = new HashMap<>();

        public <T> Builder service(Class<T> type, T instance) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(instance, "instance for " + type.getName());
            services.put(type, instance);
            return this;
        }

        public Builder serviceIfPresent(Class<?> type, Object instance) {
            if (instance != null) services.put(type, instance);
            return this;
        }

        public Builder string(String key, String value) {
            strings.put(key, value);
            return this;
        }

        public MapToolEnvironment build() {
            return new MapToolEnvironment(Map.copyOf(services), Map.copyOf(strings));
        }
    }
}
