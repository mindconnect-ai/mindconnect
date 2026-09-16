package ai.mindconnect.agent.runtime.feature;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.NamespaceRouted;
import ai.mindconnect.agent.ScopeSupplier;

import java.util.function.Function;

/**
 * How a feature turns "one repository per namespace" into the one bean the
 * core uses. A feature never decides this itself: it hands over the
 * per-namespace factory, and the routing the runtime was built with does the
 * rest — {@link #fixed one namespace for the runtime's life} in an embedded
 * library, or {@link #perScope the namespace of the current call} behind a
 * {@link ScopeSupplier} that a server binds per request and the queue per task.
 */
public interface NamespaceRouting {

    /** The bean for {@code port}: built once for the fixed namespace, or a proxy that picks the adapter per call. */
    <T> T route(Class<T> port, Function<Namespace, T> perNamespace);

    /** The namespace every adapter is built for, or {@code null} when it is chosen per call. */
    Namespace fixedNamespace();

    static NamespaceRouting fixed(Namespace namespace) {
        return new NamespaceRouting() {
            @Override public <T> T route(Class<T> port, Function<Namespace, T> perNamespace) {
                return perNamespace.apply(namespace);
            }
            @Override public Namespace fixedNamespace() { return namespace; }
            @Override public String toString() { return "NamespaceRouting.fixed(" + namespace.value() + ")"; }
        };
    }

    /** A JDK proxy per port that asks the scope for the namespace on every call and memoises one adapter per namespace. */
    static NamespaceRouting perScope(ScopeSupplier scope) {
        return new NamespaceRouting() {
            @Override public <T> T route(Class<T> port, Function<Namespace, T> perNamespace) {
                return NamespaceRouted.route(port, scope, perNamespace);
            }
            @Override public Namespace fixedNamespace() { return null; }
            @Override public String toString() { return "NamespaceRouting.perScope(" + scope + ")"; }
        };
    }
}
