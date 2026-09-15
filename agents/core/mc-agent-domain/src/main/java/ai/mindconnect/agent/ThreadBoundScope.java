package ai.mindconnect.agent;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * A {@link ScopeSupplier} that answers with whatever the current thread has
 * been bound to — the server case, where the scope is chosen per request
 * or per queued task.
 *
 * <p>Whoever enters a unit of work binds it with {@link #runIn}: a servlet
 * filter around the request, the task advisor around a queued execution, a
 * seeding routine around its start-up work. Everything underneath merely
 * asks {@link #get()}.
 *
 * <p>A thread started <em>while bound</em> inherits the binding — the
 * ThreadLocal is inheritable, so the virtual threads a turn fans out to, the
 * per-task executor a chat turn awaits on and a run the workflow admin
 * streams all work where their parent did. What does not inherit is a
 * thread created elsewhere: a pooled platform thread from start-up, the
 * queue's task threads (the task advisor binds those from the task's
 * payload). A pool that grows <em>during</em> a bound request would keep
 * that request's scope on the new thread — create pools at start-up, or
 * hand work to them through {@link #wrap}.
 *
 * <p>Unbound is an error: a server that forgot to bind would otherwise write
 * into a namespace nobody chose. A <em>fallback</em> can be given for the
 * transition while not every entry point binds yet; a host that has all
 * its entry points covered runs {@linkplain #strict() strict}.
 */
public class ThreadBoundScope implements ScopeSupplier {

    private final ThreadLocal<Scope> bound = new InheritableThreadLocal<>();
    private final Scope fallback;

    private ThreadBoundScope(Scope fallback) {
        this.fallback = fallback;
    }

    /** Unbound threads fail. The end state of every server. */
    public static ThreadBoundScope strict() {
        return new ThreadBoundScope(null);
    }

    /** Unbound threads work in {@code fallback}. For hosts whose entry points do not all bind yet. */
    public static ThreadBoundScope withFallback(Scope fallback) {
        if (fallback == null) throw new IllegalArgumentException("fallback must not be null");
        return new ThreadBoundScope(fallback);
    }

    @Override
    public Scope get() {
        Scope scope = bound.get();
        if (scope != null) return scope;
        if (fallback != null) return fallback;
        throw new IllegalStateException("No scope is bound to thread '" + Thread.currentThread().getName()
                + "' — the entry point (request filter, task advisor, start-up routine) did not bind one");
    }

    /** Whether the current thread has an explicit binding (the fallback does not count). */
    public boolean isBound() {
        return bound.get() != null;
    }

    /** Runs {@code body} with {@code scope} bound to the current thread; restores the previous binding after. */
    public <T> T runIn(Scope scope, Supplier<T> body) {
        if (scope == null) throw new IllegalArgumentException("scope must not be null");
        Scope before = bound.get();
        bound.set(scope);
        try {
            return body.get();
        } finally {
            if (before == null) bound.remove(); else bound.set(before);
        }
    }

    /** {@link #runIn(Scope, Supplier)} for a body without a result. */
    public void runIn(Scope scope, Runnable body) {
        runIn(scope, () -> {
            body.run();
            return null;
        });
    }

    /** {@link #runIn(Scope, Supplier)} for a body that throws — the task advisor's shape. */
    public <T> T callIn(Scope scope, Callable<T> body) throws Exception {
        if (scope == null) throw new IllegalArgumentException("scope must not be null");
        Scope before = bound.get();
        bound.set(scope);
        try {
            return body.call();
        } finally {
            if (before == null) bound.remove(); else bound.set(before);
        }
    }

    /** Captures the current scope for a thread hop: the returned runnable binds it wherever it runs. */
    public Runnable wrap(Runnable body) {
        Scope scope = get();
        return () -> runIn(scope, body);
    }

    /** {@link #wrap(Runnable)} for a callable. */
    public <T> Callable<T> wrap(Callable<T> body) {
        Scope scope = get();
        return () -> callIn(scope, body);
    }

    @Override
    public String toString() {
        return "ThreadBoundScope[" + (fallback == null ? "strict" : "fallback=" + fallback) + "]";
    }
}
