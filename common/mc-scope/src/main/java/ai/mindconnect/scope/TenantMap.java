package ai.mindconnect.scope;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * One value per namespace, made empty the first time a namespace asks — what
 * a store in memory keeps its data in, so that namespaces never see each
 * other's.
 */
public final class TenantMap<T> {

    private final Supplier<T> empty;
    private final Map<String, T> values = new ConcurrentHashMap<>();

    public TenantMap(Supplier<T> empty) {
        this.empty = Objects.requireNonNull(empty, "empty");
    }

    /** The value of the namespace the scope names now. */
    public T of(TenantScope scope) {
        return values.computeIfAbsent(scope.tenant().namespace(), namespace -> empty.get());
    }
}
