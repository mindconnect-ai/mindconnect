package ai.mindconnect.common.env;

import java.util.Map;
import java.util.Optional;

/**
 * The process environment, read through {@link System#getenv(String)} on
 * every call — what every placeholder resolved against before there were
 * other sources, and the last link of every server-side chain.
 */
public class SystemEnvVarResolver implements EnvVarResolver {

    static final SystemEnvVarResolver INSTANCE = new SystemEnvVarResolver();

    @Override
    public Optional<String> get(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(System.getenv(name));
    }

    @Override
    public Map<String, String> asMap() {
        return System.getenv();
    }

    @Override
    public String toString() {
        return "SystemEnvVarResolver";
    }
}
