package ai.mindconnect.kernel;

/**
 * The registry face of a kernel — what a feature sees while the kernel is
 * being built, and what a caller sees after. A host built on the kernel, the
 * agent runtime for one, implements it and adds its own facade on top.
 */
public interface Kernel {

    /** Every bean any feature registered, decorated as the core uses it. */
    Beans beans();

    /** The installed features, by class and by name. */
    Features features();

    /** Shorthand for {@code features().get(type)}. */
    default <F extends Feature> F feature(Class<F> type) {
        return features().get(type);
    }
}
