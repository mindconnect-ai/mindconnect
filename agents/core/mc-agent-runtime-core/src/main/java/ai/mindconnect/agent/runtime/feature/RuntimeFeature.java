package ai.mindconnect.agent.runtime.feature;

import java.util.Set;

/**
 * One installable capability of an agent runtime — tools, skills, workflows,
 * file upload, a task queue, … — the way a Jackson {@code Module} is one
 * installable capability of an {@code ObjectMapper}.
 *
 * <p>A feature owns three things: the <b>beans</b> it contributes (registered
 * through the {@link FeatureContext}), its <b>configuration</b> (fluent
 * setters on the feature instance, called before it is installed) and its
 * <b>cross-cutting advisors</b> (task advisors, tool advisors, cleaners —
 * {@link FeatureContext#contribute contributed} to the lists the core reads).
 *
 * <p>Features register centrally with the runtime and state what they need:
 * {@link #dependsOn()} names the feature classes that must already be
 * installed. Installing a feature whose dependency is missing is an error, not
 * an auto-install — the list of installed features <em>is</em> the runtime's
 * configuration, and a dependency pulled in silently would hide part of it.
 *
 * <p>{@link #configure} runs once, after every dependency's {@code configure}
 * and before the runtime is built. What it registers is resolved lazily, so a
 * feature may reference a bean a later feature will decorate.
 */
public interface RuntimeFeature {

    /**
     * Stable name, e.g. {@code "tools"}. Installing a second feature under the
     * same name replaces the first — that is how a configured instance takes
     * the place of a default.
     */
    String name();

    /** Feature classes that must be installed before this one. Empty by default. */
    default Set<Class<? extends RuntimeFeature>> dependsOn() {
        return Set.of();
    }

    /** Registers this feature's beans, advisors, properties and lifecycle hooks. */
    void configure(FeatureContext ctx);
}
