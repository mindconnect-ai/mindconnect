package ai.mindconnect.plugin;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The plugins of one host, what they share and the hooks they registered.
 * Knows nothing about what it hosts.
 *
 * <p>Plugins share two kinds of things, and a type is one or the other. A
 * <b>service</b> has exactly one answer — the model gateway, the session
 * store — and providing it again replaces it. A <b>contribution</b> is one
 * of many — a tool source, a layer of settings — and every plugin's is
 * kept.
 */
public interface PluginRegistry extends AutoCloseable {

    PluginRegistry install(Plugin plugin);

    /** The installed plugin of the given class. */
    <P extends Plugin> P plugin(Class<P> type);

    /** Declares a plugin the host must not run without; {@link #start()} fails if it is missing. */
    void require(Class<? extends Plugin> type);

    // ── services: exactly one answer per type ──────────────────────────────

    /** Offers a service as a lazy singleton; providing a type again replaces it. */
    <T> void provide(Class<T> type, Supplier<? extends T> factory);

    /** The service of the given type; missing is an error. */
    <T> T get(Class<T> type);

    <T> Optional<T> find(Class<T> type);

    /**
     * The service of the given type, looked up when it is asked for and not
     * now. For a class that is built before its service is provided, or
     * whose service may be replaced later — it takes the supplier in its
     * constructor and still never sees the registry.
     */
    <T> Supplier<T> supplier(Class<T> type);

    // ── contributions: every plugin's is kept ──────────────────────────────

    /** Adds one of many under a type. */
    <T> void contribute(Class<T> type, T contribution);

    /** Everything contributed under the type, in the order contributed; empty when nothing was. */
    <T> List<T> all(Class<T> type);

    // ── hooks: any number may take part ────────────────────────────────────

    /** Before the work: may change what goes in, or stop the run. A failing hook fails the run. */
    <C, R> void before(HookPoint<C, R> point, BeforeHook<C, R> hook);

    /** After the work: may change what comes out. A failing hook fails the run. */
    <C, R> void after(HookPoint<C, R> point, AfterHook<C, R> hook);

    /** Around the work itself. A failing hook fails the run. */
    <C, R> void around(HookPoint<C, R> point, AroundHook<C, R> hook);

    /** Observation: told how every run of the point ended. A failing listener breaks nothing. */
    <C, R> void listen(HookPoint<C, R> point, BiConsumer<C, R> listener);

    /**
     * Runs a point around {@code work}:
     *
     * <ol>
     *   <li>the before-hooks, in the order registered — each gets the
     *       context the one before it let pass; the first that stops ends
     *       the run with its result;</li>
     *   <li>the work, inside the around-hooks, the first registered
     *       outermost;</li>
     *   <li>the after-hooks, in reverse order — who came first before the
     *       work comes last after it, as nested calls would;</li>
     *   <li>the listeners, always, also for a run that was stopped: who
     *       keeps a record wants the refused calls in it.</li>
     * </ol>
     */
    <C, R> R run(HookPoint<C, R> point, C context, Function<C, R> work);

    // ── lifecycle ──────────────────────────────────────────────────────────

    /** Checks that every required plugin and service is there, then starts the plugins. */
    void start();

    @Override
    void close();
}
