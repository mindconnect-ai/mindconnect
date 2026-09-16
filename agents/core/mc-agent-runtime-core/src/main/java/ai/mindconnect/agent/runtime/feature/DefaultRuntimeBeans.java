package ai.mindconnect.agent.runtime.feature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The runtime's bean registry: lazy singletons with stacked decorators and
 * contribution lists. Deliberately small — no reflection, no scanning, no
 * scopes. A bean is built on its first {@code get}, decorators applied
 * innermost-first in registration order, and cached; a cycle in the
 * factories is reported with the chain of types that closes it.
 *
 * <p>Registration is only open until {@link #freeze()}; after that the
 * registry is read-only, as a runtime's wiring is after {@code build()}.
 */
public class DefaultRuntimeBeans implements RuntimeBeans {

    private final Map<Class<?>, Supplier<?>> factories = new LinkedHashMap<>();
    private final Map<Class<?>, List<UnaryOperator<Object>>> decorators = new LinkedHashMap<>();
    private final Map<Class<?>, List<Object>> contributions = new LinkedHashMap<>();
    private final Map<Class<?>, Object> singletons = new LinkedHashMap<>();
    /** Types whose factory is running on the current thread's resolution chain — for cycle reports. */
    private final Set<Class<?>> resolving = new LinkedHashSet<>();
    /** Consulted for a type nobody registered — a host container, for beans that live outside the runtime. */
    private volatile java.util.function.Function<Class<?>, Optional<?>> fallback = type -> Optional.empty();
    private boolean frozen;

    /** Where {@link #find} looks when no feature registered the type; the host's own beans, typically. */
    public void fallback(java.util.function.Function<Class<?>, Optional<?>> fallback) {
        this.fallback = fallback == null ? type -> Optional.empty() : fallback;
    }

    public synchronized <T> void register(Class<T> type, Supplier<? extends T> factory) {
        requireOpen();
        if (singletons.containsKey(type)) {
            throw new FeatureException("Bean " + type.getName() + " was already built; nothing can replace it now");
        }
        factories.put(type, factory);
    }

    @SuppressWarnings("unchecked")
    public synchronized <T> void decorate(Class<T> type, UnaryOperator<T> decorator) {
        requireOpen();
        if (singletons.containsKey(type)) {
            throw new FeatureException("Bean " + type.getName() + " was already built; a decorator would not apply to it");
        }
        decorators.computeIfAbsent(type, t -> new ArrayList<>()).add((UnaryOperator<Object>) decorator);
    }

    public synchronized <T> void contribute(Class<T> type, T item) {
        requireOpen();
        contributions.computeIfAbsent(type, t -> new ArrayList<>()).add(item);
    }

    /** Closes registration; reads keep working. */
    public synchronized void freeze() {
        frozen = true;
    }

    @Override
    public synchronized <T> T get(Class<T> type) {
        return find(type).orElseThrow(() -> new FeatureException(
                "No bean of type " + type.getName() + " — no installed feature registered one"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> Optional<T> find(Class<T> type) {
        Object built = singletons.get(type);
        if (built != null) return Optional.of((T) built);
        Supplier<?> factory = factories.get(type);
        if (factory == null) return (Optional<T>) fallback.apply(type);
        if (!resolving.add(type)) {
            throw new FeatureException("Beans need each other to be built: "
                    + chain(type) + " → " + type.getSimpleName());
        }
        try {
            Object instance = factory.get();
            if (instance == null) {
                throw new FeatureException("The factory of bean " + type.getName() + " returned null");
            }
            for (UnaryOperator<Object> decorator : decorators.getOrDefault(type, List.of())) {
                instance = decorator.apply(instance);
            }
            singletons.put(type, instance);
            return Optional.of((T) instance);
        } finally {
            resolving.remove(type);
        }
    }

    @Override
    public synchronized boolean has(Class<?> type) {
        return singletons.containsKey(type) || factories.containsKey(type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> List<T> all(Class<T> type) {
        return (List<T>) List.copyOf(contributions.getOrDefault(type, List.of()));
    }

    /**
     * The bean of the given type <em>if it has already been built</em> — never
     * building it. What a close hook asks: a runtime that never resolved a
     * resource has nothing to release, and resolving one to close it would
     * start what shutdown is ending.
     */
    @SuppressWarnings("unchecked")
    public synchronized <T> Optional<T> ifBuilt(Class<T> type) {
        return Optional.ofNullable((T) singletons.get(type));
    }

    /** The beans built so far, in build order — for closing resources and for diagnostics. */
    public synchronized List<Object> built() {
        return List.copyOf(singletons.values());
    }

    private void requireOpen() {
        if (frozen) {
            throw new FeatureException("The runtime is built; its beans can no longer be registered or decorated");
        }
    }

    private String chain(Class<?> closing) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> type : resolving) {
            if (!sb.isEmpty()) sb.append(" → ");
            sb.append(type.getSimpleName());
        }
        return sb.toString();
    }
}
