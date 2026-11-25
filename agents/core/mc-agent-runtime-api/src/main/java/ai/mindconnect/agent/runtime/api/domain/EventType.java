package ai.mindconnect.agent.runtime.api.domain;

import java.util.Map;
import java.util.function.Function;

/**
 * One kind of {@link SessionEvent}: its name, and how the values of an
 * event are read as a typed form and written from one. The runtime's own
 * kinds are the constants of {@link StreamEvent}; a plugin keeps the
 * constants of its kinds itself — whoever knows the plugin reads its events
 * with them, and nothing has to be registered.
 *
 * @param <T> the typed form of an event of this kind
 */
public record EventType<T>(String kind, Function<Map<String, Object>, T> reader,
                           Function<T, Map<String, Object>> writer) {

    T read(Map<String, Object> details) {
        return reader.apply(details);
    }

    Map<String, Object> write(T payload) {
        return writer.apply(payload);
    }
}
