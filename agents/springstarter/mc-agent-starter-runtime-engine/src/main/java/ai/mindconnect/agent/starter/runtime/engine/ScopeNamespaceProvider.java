package ai.mindconnect.agent.starter.runtime.engine;

import java.util.function.Supplier;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;

/**
 * The runtime's namespace is the namespace of the application's scope: what
 * the request filter bound for the call. A worker of the queue has no
 * request, so the runtime binds the namespace a task was submitted in
 * through the same scope supplier — everything the application's stores ask
 * on that thread then answers for that namespace.
 */
final class ScopeNamespaceProvider implements NamespaceProvider {

    private final ScopeSupplier scope;

    ScopeNamespaceProvider(ScopeSupplier scope) {
        this.scope = scope;
    }

    @Override
    public String current() {
        return scope.namespace().value();
    }

    @Override
    public <T> T callIn(String namespace, Supplier<T> body) {
        try {
            return scope.callIn(Scope.of(new Namespace(namespace)), body::get);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // A Supplier throws nothing checked; only the scope supplier's own signature allows it.
            throw new IllegalStateException(e);
        }
    }
}
