package ai.mindconnect.agent.tool;

import java.util.Optional;

/**
 * Typed registry of runtime-level dependencies that {@link ToolFactory}
 * instances may request at bind time. The runtime populates it with whatever
 * services and configuration the host application wants to expose to tools
 * (repositories, HTTP clients, API keys, base directories, ...).
 *
 * <p>A factory should only pull the values it actually needs — there is no
 * shared god-context. Missing values return {@link Optional#empty()} or
 * {@code null}, and it is the factory's responsibility to decide whether that
 * makes the tool unavailable.
 */
public interface ToolEnvironment {

    /** Look up a service by type. Returns empty if no such service is registered. */
    <T> Optional<T> get(Class<T> type);

    /** Look up a named string value (e.g. an API key or base directory). */
    Optional<String> getString(String key);

    /** Required-type accessor; throws if no service of the given type is registered. */
    default <T> T require(Class<T> type) {
        return get(type).orElseThrow(() ->
                new IllegalStateException("ToolEnvironment is missing required service: " + type.getName()));
    }

    /** Required-string accessor; throws if no value is bound for the key. */
    default String requireString(String key) {
        return getString(key).orElseThrow(() ->
                new IllegalStateException("ToolEnvironment is missing required value: " + key));
    }
}
