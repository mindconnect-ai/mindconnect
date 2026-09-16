package ai.mindconnect.common.env;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sources asked in order; the first one that has the name answers. A source
 * that throws stops the lookup — a store that cannot be read is an error, not
 * an absent variable, or a user's key would silently fall back to the
 * installation's.
 */
public record ChainedEnvVarResolver(List<EnvVarResolver> sources) implements EnvVarResolver {

    public ChainedEnvVarResolver {
        if (sources == null || sources.stream().anyMatch(s -> s == null)) {
            throw new IllegalArgumentException("A chain cannot contain a null source");
        }
        sources = List.copyOf(sources);
    }

    @Override
    public Optional<String> get(String name) {
        for (EnvVarResolver source : sources) {
            Optional<String> value = source.get(name);
            if (value.isPresent()) return value;
        }
        return Optional.empty();
    }

    /** Later sources first, earlier ones written over them — so the first source that has a name wins. */
    @Override
    public Map<String, String> asMap() {
        Map<String, String> all = new LinkedHashMap<>();
        for (int i = sources.size() - 1; i >= 0; i--) {
            all.putAll(sources.get(i).asMap());
        }
        return all;
    }

    /** The chain without its personal sources; the chain itself when it has none. */
    @Override
    public EnvVarResolver shared() {
        if (sources.stream().noneMatch(EnvVarResolver::personal)) return this;
        return new ChainedEnvVarResolver(sources.stream().filter(s -> !s.personal()).toList());
    }
}
