package ai.mindconnect.agent;

import java.util.function.Supplier;

/**
 * The {@link Scope} the runtime is working in <em>right now</em>.
 *
 * <p>The namespace is the one storage partition; every repository adapter is
 * bound to one when it is built. Nothing above the adapters names it —
 * domain objects, ids, ports and services carry no namespace. What they get
 * instead is this: a way to ask, never a way to bind. How the answer comes
 * about is the host's business:
 *
 * <ul>
 *   <li>a library embedding the runtime works in one scope for its whole
 *       life — {@link #fixed(Scope)};</li>
 *   <li>a server chooses the scope per request or per queued task and
 *       binds it to the executing thread — {@link ThreadBoundScope}.</li>
 * </ul>
 *
 * <p>Adapters that serve many namespaces sit behind {@link NamespaceRouted},
 * which asks the supplier on every call.
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
