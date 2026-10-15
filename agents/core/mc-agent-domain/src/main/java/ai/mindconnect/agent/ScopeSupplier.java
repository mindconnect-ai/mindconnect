package ai.mindconnect.agent;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * The {@link Scope} the runtime is working in <em>right now</em>.
 *
 * <p>The namespace is the one storage partition; every repository adapter
 * takes this supplier and asks it on every call. Nothing above the adapters names it —
 * domain objects, ids, ports and services carry no namespace. What they get
 * instead is this: a way to ask. How the answer comes about is the host's
 * business:
 *
 * <ul>
 *   <li>a library embedding the runtime works in one scope for its whole
 *       life — {@link #fixed(Scope)};</li>
 *   <li>a server chooses the scope per request or per queued task and binds
 *       it to the executing thread ({@code ThreadBoundScopeSupplier} in
 *       {@code mc-namespace-core}).</li>
 * </ul>
 *
 * <p>Work that leaves its thread — a queued task, its completion — is
 * resumed with {@link #runIn} or {@link #callIn} in the scope it started in.
 * A scope fixed for good has nothing to bind and simply runs the body.
 */
@FunctionalInterface
public interface ScopeSupplier extends Supplier<Scope> {

    /** The scope of the current unit of work. Never {@code null}. */
    @Override
    Scope get();

    /** Shorthand for {@code get().namespace()}. */
    default Namespace namespace() {
        return get().namespace();
    }

    /**
     * Whether the scope is bound per thread, so that work leaving its thread has
     * to carry the scope along and be resumed in it. A scope fixed for good says no.
     */
    default boolean bindsPerThread() {
        return false;
    }

    /** Runs {@code body} in {@code scope}: bound to the thread where scopes are bound, as it is otherwise. */
    default void runIn(Scope scope, Runnable body) {
        body.run();
    }

    /** {@link #runIn} for a body that answers and throws — a queued task's execution. */
    default <T> T callIn(Scope scope, Callable<T> body) throws Exception {
        return body.call();
    }

    /** One scope for the life of the runtime — the library case. */
    static ScopeSupplier fixed(Scope scope) {
        if (scope == null) throw new IllegalArgumentException("scope must not be null");
        return new Fixed(scope);
    }

    /** {@link #fixed(Scope)} for one namespace on nobody's behalf. */
    static ScopeSupplier fixed(Namespace namespace) {
        return fixed(Scope.of(namespace));
    }

    /** {@link #fixed(Scope)} for {@link Scope#local()}. */
    static ScopeSupplier local() {
        return fixed(Scope.local());
    }

    /** A named record rather than a lambda so a fixed supplier prints as what it is. */
    record Fixed(Scope scope) implements ScopeSupplier {
        @Override
        public Scope get() {
            return scope;
        }
    }
}
