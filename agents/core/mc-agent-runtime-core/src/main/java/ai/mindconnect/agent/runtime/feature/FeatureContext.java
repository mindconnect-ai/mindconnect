package ai.mindconnect.agent.runtime.feature;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * What a {@link RuntimeFeature} talks to while it is being installed: the
 * runtime under construction to read from, and the registry to write to.
 *
 * <p><b>Reading.</b> {@link #runtime()} is the runtime being built — its
 * {@link RuntimeView#beans() beans} and {@link RuntimeView#features() features}
 * work here exactly as they do for a caller after {@code build()}. A feature
 * reads another feature's beans the way anyone else does; declaring that
 * feature in {@link RuntimeFeature#dependsOn()} is what guarantees the read
 * never finds it missing.
 *
 * <p><b>Writing.</b> {@link #bean} registers a lazy singleton, {@link #decorate}
 * wraps one registered by someone else (encryption, namespace routing, access
 * control), {@link #contribute} adds to a list the core collects (advisors,
 * workers, prompt providers), {@link #property} publishes a plain string for
 * adapters and the tool environment. {@link #onStart} and {@link #onClose}
 * hook the runtime's lifecycle.
 */
public interface FeatureContext {

    // ── reading ────────────────────────────────────────────────────────────

    /** The runtime under construction. Its facade methods (chat, sessions) are not usable yet; its registries are. */
    RuntimeView runtime();

    /** Where this runtime keeps its data; every feature picks its adapter by switching over it. */
    Persistence persistence();

    /** The one mapper the runtime writes JSON with — file stores and Postgres documents share it. */
    ObjectMapper objectMapper();

    /** Shorthand for {@code runtime().beans().get(type)}. */
    default <T> T require(Class<T> type) {
        return runtime().beans().get(type);
    }

    /** Shorthand for {@code runtime().beans().find(type)}. */
    default <T> Optional<T> find(Class<T> type) {
        return runtime().beans().find(type);
    }

    /** A plain-string setting published by the builder or a feature; see {@link #property(String, String)}. */
    Optional<String> property(String key);

    /** Every property published so far — what adapters that take a settings map, and the tool environment, read. */
    java.util.Map<String, String> properties();

    // ── writing ────────────────────────────────────────────────────────────

    /**
     * Registers a lazy singleton: the factory runs on the first {@code get},
     * the result is cached for the runtime's life. Registering a type twice
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
     * Adds to the list of the given type, read with {@link RuntimeBeans#all}.
     * For the many-of-a-kind: task advisors, tool advisors, workers, prompt
     * context providers, cleaners.
     */
    <T> void contribute(Class<T> type, T item);

    /** Publishes a plain string, visible to every feature and to the tool environment. */
    void property(String key, String value);

    /** Runs after every feature's {@code configure}, in installation order — schema, seeds, worker registration. */
    void onStart(Runnable hook);

    /** Runs when the runtime closes, in reverse installation order. */
    void onClose(AutoCloseable hook);
}
