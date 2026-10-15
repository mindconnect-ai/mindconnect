package ai.mindconnect.kernel;


import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * What a {@link Feature} talks to while it is being installed: the
 * kernel under construction to read from, and the registry to write to.
 *
 * <p><b>Reading.</b> {@link #kernel()} is the kernel being built — its
 * {@link Kernel#beans() beans} and {@link Kernel#features() features}
 * work here exactly as they do for a caller after {@code build()}. What a host
 * hands every feature — its settings, its mapper, where it keeps data — is a
 * bean like any other, registered before the first feature is configured. A feature
 * reads another feature's beans the way anyone else does; declaring that
 * feature in {@link Feature#dependsOn()} is what guarantees the read
 * never finds it missing.
 *
 * <p><b>Writing.</b> {@link #bean} registers a lazy singleton, {@link #decorate}
 * wraps one registered by someone else (encryption, access control), {@link #contribute} adds to a list the core collects (advisors,
 * workers, prompt providers), {@link #property} publishes a plain string for
 * adapters and the tool environment. {@link #onStart} and {@link #onClose}
 * hook the kernel's lifecycle.
 */
public interface FeatureContext {

    // ── reading ────────────────────────────────────────────────────────────

    /** The kernel under construction: its registries work, whatever a host builds on top of it does not yet. */
    Kernel kernel();

    /** Shorthand for {@code kernel().beans().get(type)}. */
    default <T> T require(Class<T> type) {
        return kernel().beans().get(type);
    }

    /** Shorthand for {@code kernel().beans().find(type)}. */
    default <T> Optional<T> find(Class<T> type) {
        return kernel().beans().find(type);
    }

    /** A plain-string setting published by the builder or a feature; see {@link #property(String, String)}. */
    Optional<String> property(String key);

    /** Every property published so far — what adapters that take a settings map, and the tool environment, read. */
    java.util.Map<String, String> properties();

    // ── writing ────────────────────────────────────────────────────────────

    /**
     * Registers a lazy singleton: the factory runs on the first {@code get},
     * the result is cached for the kernel's life. Registering a type twice
     * replaces the factory (a configured feature over a default), not the
     * decorators stacked on it.
     */
    <T> void bean(Class<T> type, Supplier<? extends T> factory);

    /** Registers an existing instance as a bean. */
    default <T> void instance(Class<T> type, T instance) {
        Supplier<T> factory = () -> instance;
        bean(type, factory);
    }

    /**
     * Wraps the bean of the given type when it is first resolved. Decorators
     * are applied in registration order, so the last registered is outermost.
     * The type need not be registered yet — a feature may decorate what a
     * later one provides.
     */
    <T> void decorate(Class<T> type, UnaryOperator<T> decorator);

    /**
     * Adds to the list of the given type, read with {@link Beans#all}.
     * For the many-of-a-kind: task advisors, tool advisors, workers, prompt
     * context providers, cleaners.
     */
    <T> void contribute(Class<T> type, T item);

    /** Publishes a plain string, visible to every feature and to the tool environment. */
    void property(String key, String value);

    /** Runs after every feature's {@code configure}, in installation order — schema, seeds, worker registration. */
    void onStart(Runnable hook);

    /** Runs when the kernel closes, in reverse installation order. */
    void onClose(AutoCloseable hook);
}
