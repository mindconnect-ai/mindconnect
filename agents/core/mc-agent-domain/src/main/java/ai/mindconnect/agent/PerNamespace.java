package ai.mindconnect.agent;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * One value per namespace, made on first use — what an in-memory store keeps
 * instead of a single map, so that one instance serves every namespace the
 * way the file and Postgres adapters do, and a namespace never sees another's
 * entries.
 */
public final class PerNamespace<T> {

    private final ScopeSupplier scope;
    private final Supplier<T> empty;
    private final Map<Namespace, T> values = new ConcurrentHashMap<>();

    /**
     * @param scope names the namespace of the current call
     * @param empty makes the value of a namespace seen for the first time
     */
    public PerNamespace(ScopeSupplier scope, Supplier<T> empty) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.empty = Objects.requireNonNull(empty, "empty");
    }

    /** The value of the namespace the current call works in. */
    public T current() {
        return values.computeIfAbsent(scope.namespace(), namespace -> empty.get());
    }
}
