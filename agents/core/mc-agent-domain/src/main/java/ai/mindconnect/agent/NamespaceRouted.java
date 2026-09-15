package ai.mindconnect.agent;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * One port, many namespaces: a proxy that keeps an adapter instance per
 * namespace and forwards every call to the one for the namespace the
 * {@link ScopeSupplier} names at that moment.
 *
 * <p>The adapters themselves stay as they are — bound to one namespace in
 * their constructor, ignorant of any other. The proxy is what a Spring
 * starter hands out as the port bean:
 *
 * <pre>
 * AgentDefinitionRepository repo = NamespaceRouted.route(AgentDefinitionRepository.class, scope,
 *         ns -&gt; new FileAgentDefinitionRepository(dir, mapper, ns));
 * </pre>
 *
 * <p>Instances are created lazily on the first call for a namespace and
 * kept for the life of the proxy; {@link #evict} drops one (a deleted
 * namespace). The factory may do set-up work — a Postgres adapter's
 * {@code initSchema()} runs once per namespace and process.
 */
public final class NamespaceRouted {

    private NamespaceRouted() {
    }

    /**
     * @param port      the interface the proxy implements — a repository port, a gateway
     * @param scope     who says which namespace a call belongs to
     * @param factory   builds the adapter for one namespace; called at most once per namespace
     */
    public static <T> T route(Class<T> port, ScopeSupplier scope, Function<Namespace, T> factory) {
        if (!port.isInterface()) {
            throw new IllegalArgumentException(port.getName() + " is not an interface — only ports can be routed");
        }
        Handler<T> handler = new Handler<>(port, scope, factory);
        return port.cast(Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[]{port, Routed.class}, handler));
    }

    /** Forgets the instance for {@code namespace}; the next call builds a fresh one. */
    public static void evict(Object routed, Namespace namespace) {
        if (routed instanceof Routed r) r.evict(namespace);
    }

    /** The handle a routed proxy also implements, for {@link #evict}. */
    public interface Routed {
        void evict(Namespace namespace);
    }

    private static final class Handler<T> implements InvocationHandler, Routed {

        private final Class<T> port;
        private final ScopeSupplier scope;
        private final Function<Namespace, T> factory;
        private final Map<Namespace, T> instances = new ConcurrentHashMap<>();

        Handler(Class<T> port, ScopeSupplier scope, Function<Namespace, T> factory) {
            this.port = port;
            this.scope = scope;
            this.factory = factory;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Routed.class) {
                evict((Namespace) args[0]);
                return null;
            }
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "NamespaceRouted[" + port.getSimpleName() + " via " + scope + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            T target = instances.computeIfAbsent(scope.namespace(), factory);
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        @Override
        public void evict(Namespace namespace) {
            instances.remove(namespace);
        }
    }
}
