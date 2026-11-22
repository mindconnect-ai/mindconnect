package ai.mindconnect.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PluginRegistry} in memory, for one process. Installing is one
 * thread's business while the host comes up; running hooks and looking up
 * services is safe from any thread afterwards.
 */
public final class DefaultPluginRegistry implements PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultPluginRegistry.class);

    /** What is registered at one hook point, each kind in the order registered. */
    private static final class Hooks {
        private final List<BeforeHook<?, ?>> before = new CopyOnWriteArrayList<>();
        private final List<AfterHook<?, ?>> after = new CopyOnWriteArrayList<>();
        private final List<AroundHook<?, ?>> around = new CopyOnWriteArrayList<>();
        private final List<BiConsumer<?, ?>> listeners = new CopyOnWriteArrayList<>();
    }

    private final Map<String, Plugin> plugins = new LinkedHashMap<>();
    private final Set<Class<? extends Plugin>> required = new LinkedHashSet<>();
    private final Map<Class<?>, Supplier<?>> factories = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();
    private final Map<Class<?>, List<Object>> contributions = new ConcurrentHashMap<>();
    private final Map<String, Hooks> hooks = new ConcurrentHashMap<>();
    private boolean started;

    @Override
    public synchronized PluginRegistry install(Plugin plugin) {
        if (started) {
            throw new IllegalStateException("Registry already started; cannot install " + plugin.name());
        }
        for (Class<? extends Plugin> dependency : plugin.dependsOn()) {
            if (plugins.values().stream().noneMatch(dependency::isInstance)) {
                throw new IllegalStateException(
                        plugin.name() + " needs " + dependency.getSimpleName() + " to be installed first");
            }
        }
        plugins.put(plugin.name(), plugin);
        plugin.install(this);
        return this;
    }

    @Override
    public synchronized <P extends Plugin> P plugin(Class<P> type) {
        return plugins.values().stream().filter(type::isInstance).map(type::cast).findFirst()
                .orElseThrow(() -> new IllegalStateException("Plugin not installed: " + type.getSimpleName()));
    }

    @Override
    public synchronized void require(Class<? extends Plugin> type) {
        required.add(type);
    }

    // ── services ────────────────────────────────────────────────────────────

    @Override
    public <T> void provide(Class<T> type, Supplier<? extends T> factory) {
        factories.put(type, factory);
        services.remove(type);
    }

    @Override
    public <T> T get(Class<T> type) {
        return find(type).orElseThrow(() -> new IllegalStateException("No service provided: " + type.getName()));
    }

    @Override
    public <T> Optional<T> find(Class<T> type) {
        Object service = services.get(type);
        if (service == null) {
            Supplier<?> factory = factories.get(type);
            if (factory == null) {
                return Optional.empty();
            }
            service = factory.get();
            services.put(type, service);
        }
        return Optional.of(type.cast(service));
    }

    @Override
    public <T> Supplier<T> supplier(Class<T> type) {
        return () -> get(type);
    }

    // ── contributions ───────────────────────────────────────────────────────

    @Override
    public <T> void contribute(Class<T> type, T contribution) {
        contributions.computeIfAbsent(type, key -> new CopyOnWriteArrayList<>()).add(contribution);
    }

    @Override
    public <T> List<T> all(Class<T> type) {
        return contributions.getOrDefault(type, List.of()).stream().map(type::cast).toList();
    }

    // ── hooks ───────────────────────────────────────────────────────────────

    @Override
    public <C, R> void before(HookPoint<C, R> point, BeforeHook<C, R> hook) {
        hooksAt(point).before.add(hook);
    }

    @Override
    public <C, R> void after(HookPoint<C, R> point, AfterHook<C, R> hook) {
        hooksAt(point).after.add(hook);
    }

    @Override
    public <C, R> void around(HookPoint<C, R> point, AroundHook<C, R> hook) {
        hooksAt(point).around.add(hook);
    }

    @Override
    public <C, R> void listen(HookPoint<C, R> point, BiConsumer<C, R> listener) {
        hooksAt(point).listeners.add(listener);
    }

    private Hooks hooksAt(HookPoint<?, ?> point) {
        return hooks.computeIfAbsent(point.name(), name -> new Hooks());
    }

    /*
     * The casts: a point's name is its identity, its type parameters exist
     * only at compile time. Hooks registered through a HookPoint<C, R> are
     * hooks for C and R, as long as a module declares each name once.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <C, R> R run(HookPoint<C, R> point, C context, Function<C, R> work) {
        Hooks registered = hooks.get(point.name());
        if (registered == null) {
            return work.apply(context);
        }

        C passed = context;
        R result = null;
        boolean stopped = false;
        for (BeforeHook<?, ?> hook : registered.before) {
            switch (((BeforeHook<C, R>) hook).before(passed)) {
                case BeforeOutcome.Proceed<C, R> proceed -> passed = proceed.context();
                case BeforeOutcome.Stop<C, R> stop -> {
                    result = stop.result();
                    stopped = true;
                }
            }
            if (stopped) {
                break;
            }
        }

        if (!stopped) {
            Function<C, R> wrapped = work;
            for (int i = registered.around.size() - 1; i >= 0; i--) {
                AroundHook<C, R> hook = (AroundHook<C, R>) registered.around.get(i);
                Function<C, R> next = wrapped;
                wrapped = input -> hook.around(input, next);
            }
            result = wrapped.apply(passed);
            for (int i = registered.after.size() - 1; i >= 0; i--) {
                result = ((AfterHook<C, R>) registered.after.get(i)).after(passed, result);
            }
        }

        for (BiConsumer<?, ?> listener : registered.listeners) {
            try {
                ((BiConsumer<C, R>) listener).accept(passed, result);
            } catch (RuntimeException e) {
                // Observation never breaks the run; what is lost is one
                // listener's record of it, and that is said here.
                log.warn("A listener at {} failed: {}", point.name(), e.toString());
            }
        }
        return result;
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    @Override
    public synchronized void start() {
        for (Class<? extends Plugin> type : required) {
            if (plugins.values().stream().noneMatch(type::isInstance)) {
                throw new IllegalStateException("Required plugin is not installed: " + type.getSimpleName());
            }
        }
        // All of them at once: an operator who is told one missing service
        // per start fixes a configuration five times.
        List<String> missing = new ArrayList<>();
        for (Plugin plugin : plugins.values()) {
            for (Class<?> service : plugin.requires()) {
                if (!factories.containsKey(service)) {
                    missing.add(plugin.name() + " needs " + service.getSimpleName());
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("No plugin provides a required service: "
                    + missing.stream().collect(Collectors.joining("; ")));
        }
        if (!started) {
            plugins.values().forEach(Plugin::start);
            started = true;
        }
    }

    @Override
    public synchronized void close() {
        List<Plugin> reversed = new ArrayList<>(plugins.values());
        Collections.reverse(reversed);
        for (Plugin plugin : reversed) {
            try {
                plugin.close();
            } catch (RuntimeException e) {
                // One plugin that cannot close must not keep the others open.
                log.warn("Closing plugin {} failed: {}", plugin.name(), e.toString());
            }
        }
    }
}
