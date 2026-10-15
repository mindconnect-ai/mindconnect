package ai.mindconnect.kernel;

import java.util.List;
import java.util.Optional;

/** The features installed in a kernel, addressed by class or by name. */
public interface Features {

    /**
     * The installed instance of the given feature class — the object that was
     * passed to {@code install}, or the default. Not installed is an error,
     * not an empty answer: a caller who only wants to know asks {@link #has}.
     */
    <F extends Feature> F get(Class<F> type) throws FeatureException;

    /** The installed feature assignable to the given class, if any. */
    <F extends Feature> Optional<F> find(Class<F> type);

    /** The installed feature of the given name, if any. */
    Optional<Feature> byName(String name);

    default boolean has(Class<? extends Feature> type) {
        return find(type).isPresent();
    }

    /** All installed features, in installation order. */
    List<Feature> all();
}
