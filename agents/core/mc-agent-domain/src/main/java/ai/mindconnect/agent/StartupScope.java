package ai.mindconnect.agent;

import java.util.function.Supplier;

/**
 * Runs start-up work in the installation's default namespace.
 *
 * <p>A server's scope is {@link ThreadBoundScope#strict() strict}: a thread
 * that touches a store without a bound scope fails, because on a server that
 * silence is a leak between namespaces. Requests bind through the namespace
 * filter and tasks through the runtime's advisor, but the threads that run
 * before either exists — the runtime build, the tool warm-up, the seed
 * loaders — bind nothing. They come through here instead of each inventing
 * its own wrapper, so the rule ("start-up works in the default namespace")
 * lives in one place.
 *
 * <p>Does nothing when there is nothing to bind: a runtime fixed to one
 * namespace, a scope somebody already bound, or no scope at all.
 */
public class StartupScope {

    private StartupScope() {
    }

    public static void run(ScopeSupplier scope, Namespace defaultNamespace, Runnable body) {
        call(scope, defaultNamespace, () -> {
            body.run();
            return null;
        });
    }

    public static <T> T call(ScopeSupplier scope, Namespace defaultNamespace, Supplier<T> body) {
        if (scope instanceof ThreadBoundScope bound && !bound.isBound()) {
            return bound.runIn(Scope.of(defaultNamespace), body);
        }
        return body.get();
    }
}
