package ai.mindconnect.agent.runtime.feature;

import java.util.List;
import java.util.Optional;

/**
 * The bean registry of a runtime: lazy singletons by type, plus the lists
 * features {@link FeatureContext#contribute contribute} to.
 */
public interface RuntimeBeans {

    /** The bean of the given type, built on first use. */
    <T> T get(Class<T> type) throws FeatureException;

    /** The bean of the given type, or empty when nothing registered it. */
    <T> Optional<T> find(Class<T> type);

    /** Whether something registered a bean of the given type. */
    boolean has(Class<?> type);

    /** Every contribution of the given type, in contribution order; empty when there are none. */
    <T> List<T> all(Class<T> type);
}
