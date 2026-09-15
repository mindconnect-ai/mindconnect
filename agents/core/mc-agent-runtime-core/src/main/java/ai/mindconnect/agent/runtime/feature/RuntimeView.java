package ai.mindconnect.agent.runtime.feature;

/**
 * The registry face of an agent runtime — what a feature sees while the
 * runtime is being built, and what a caller sees after. The builder's
 * {@code AgentRuntime} implements it and adds the chat facade on top.
 */
public interface RuntimeView {

    /** Every bean any feature registered, decorated as the core uses it. */
    RuntimeBeans beans();

    /** The installed features, by class and by name. */
    Features features();

    /** Shorthand for {@code features().get(type)}. */
    default <F extends RuntimeFeature> F feature(Class<F> type) {
        return features().get(type);
    }
}
