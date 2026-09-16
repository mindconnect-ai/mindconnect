package ai.mindconnect.common.env;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A fixed set of variables. A {@code null} value counts as absent, so a map
 * that carries one for a name lets the next source of a chain answer.
 */
public record MapEnvVarResolver(Map<String, String> vars) implements EnvVarResolver {

    static final MapEnvVarResolver EMPTY = new MapEnvVarResolver(Map.of());

    public MapEnvVarResolver {
        vars = vars == null ? Map.of() : Map.copyOf(withoutNullValues(vars));
    }

    @Override
    public Optional<String> get(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(vars.get(name));
    }

    @Override
    public Map<String, String> asMap() {
        return vars;
    }

    private static Map<String, String> withoutNullValues(Map<String, String> vars) {
        if (vars.values().stream().noneMatch(v -> v == null)) return vars;
        var copy = new LinkedHashMap<String, String>();
        vars.forEach((k, v) -> { if (k != null && v != null) copy.put(k, v); });
        return copy;
    }
}
