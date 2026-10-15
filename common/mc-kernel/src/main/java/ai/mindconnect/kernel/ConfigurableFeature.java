package ai.mindconnect.kernel;

/**
 * Base for a feature with fluent settings. The settings are read when the
 * feature is {@link #configure configured} into a kernel; a setter called
 * after that would change a field nobody reads any more, so {@link #changing()}
 * refuses it with a message instead of letting it silently do nothing. The
 * instance stays reachable through {@code kernel.feature(X.class)} — to read
 * its settings and to reach its beans; what changes while it runs is data, and
 * that goes through the beans.
 */
public abstract class ConfigurableFeature implements Feature {

    private boolean configured;

    /** Called by every setter: refuses a change once the feature is built into a kernel. */
    protected void changing() {
        if (configured) {
            throw new FeatureException(getClass().getSimpleName() + " (\"" + name() + "\") is built into a kernel;"
                    + " its settings cannot change any more — talk to its beans instead");
        }
    }

    /** Whether this feature has been configured into a kernel. */
    public boolean configured() {
        return configured;
    }

    @Override
    public final void configure(FeatureContext ctx) {
        changing();
        configured = true;
        install(ctx);
    }

    /** What {@link #configure} does once the settings are frozen. */
    protected abstract void install(FeatureContext ctx);
}
