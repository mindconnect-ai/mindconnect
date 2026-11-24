package ai.mindconnect.agent.scope;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * One value per namespace, made on first use — what an in-memory store keeps
 * instead of a single map, so that one instance serves every namespace the
 * way the file and Postgres adapters do, and a namespace never sees another's
 * entries.
 *
 * <p>It only keeps: which namespace a call works in is the caller's to ask
 * its {@link ScopeSupplier}, so the line that reads an entry shows where the
 * namespace comes from.
 */
public final class NamespaceMap<T> {

    private final Supplier<T> empty;
    private final Map<Namespace, T> values = new ConcurrentHashMap<>();

    /**
     * @param empty makes the value of a namespace seen for the first time
     */
    public NamespaceMap(Supplier<T> empty) {
        this.empty = Objects.requireNonNull(empty, "empty");
    }

    /** The value of {@code namespace}. */
    public T of(Namespace namespace) {
        return values.computeIfAbsent(namespace, seen -> empty.get());
    }
}
