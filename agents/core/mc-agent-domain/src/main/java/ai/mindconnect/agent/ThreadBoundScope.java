package ai.mindconnect.agent;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
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
 * ThreadLocal is inheritable, so the virtual threads a turn fans out to and
 * a run the workflow admin streams work where their parent did. What does not inherit is a
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
 *
 * <p>Whoever has to know when work enters a namespace — to prepare it once,
 * the first time it is used after start (see {@link NamespaceFirstUse}) —
 * registers with {@link #onBind}. Every entry point binds through here, so
 * that one hook sees requests, queued tasks and start-up routines alike.
 */
public class ThreadBoundScope implements ScopeSupplier {

    private final ThreadLocal<Scope> bound = new InheritableThreadLocal<>();
    private final Scope fallback;
    private final List<Consumer<Scope>> bindListeners = new CopyOnWriteArrayList<>();

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
            entered(before, scope);
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
            entered(before, scope);
            return body.call();
        } finally {
            if (before == null) bound.remove(); else bound.set(before);
        }
    }

    /**
     * Calls {@code listener} whenever a thread enters a namespace through
     * {@link #runIn} or {@link #callIn} — with the new scope already bound, before
     * the body runs. Not when a nested binding stays in the namespace the
     * thread already works in: that is no new entry. The listener runs on the
     * binding thread, on every such entry, so it has to be cheap after the first
     * time; an exception it throws fails the unit of work it was called for.
     */
    public void onBind(Consumer<Scope> listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        bindListeners.add(listener);
    }

    private void entered(Scope before, Scope scope) {
        if (bindListeners.isEmpty()) return;
        if (before != null && before.namespace().equals(scope.namespace())) return;
        for (Consumer<Scope> listener : bindListeners) {
            listener.accept(scope);
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
