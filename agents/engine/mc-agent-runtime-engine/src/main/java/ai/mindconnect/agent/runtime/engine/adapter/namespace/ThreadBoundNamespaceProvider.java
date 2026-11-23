package ai.mindconnect.agent.runtime.engine.adapter.namespace;

import java.util.function.Supplier;

import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;

/**
 * The namespace of the thread: bound for the length of a request by
 * whoever receives it, and by the engine's workers for the length of a
 * task. A thread nobody bound has none and is told so — there is no default
 * to fall into.
 */
public final class ThreadBoundNamespaceProvider implements NamespaceProvider {

    private final ThreadLocal<String> bound = new ThreadLocal<>();

    @Override
    public String current() {
        String namespace = bound.get();
        if (namespace == null) {
            throw new IllegalStateException("No namespace is bound to this thread");
        }
        return namespace;
    }

    @Override
    public <T> T callIn(String namespace, Supplier<T> body) {
        String before = bound.get();
        bound.set(namespace);
        try {
            return body.get();
        } finally {
            if (before == null) {
                bound.remove();
            } else {
                bound.set(before);
            }
        }
    }
}
