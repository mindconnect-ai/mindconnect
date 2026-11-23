package ai.mindconnect.agent.runtime.api.port.out;

import java.util.function.Supplier;

/**
 * Says in which namespace the running code works — a tenant on a server,
 * one fixed name on a single machine. A session does not carry its
 * namespace: the same session id may exist in several, and which one is
 * meant is a matter of who is asking. Stores and resolvers ask here and
 * keep what they hold apart by the answer.
 *
 * <p>The answer belongs to the running code, not to an object, so it has to
 * be carried wherever work moves to another thread or process: whoever
 * hands work on notes {@link #current()} and runs the work in
 * {@link #callIn}.
 */
public interface NamespaceProvider {

    /**
     * The namespace of the code that asks.
     *
     * @throws IllegalStateException if there is none — on a server working
     *         in "some default" namespace would be a leak, not a fallback
     */
    String current();

    /** Runs the body with {@code namespace} as the current one, and restores what was current before. */
    <T> T callIn(String namespace, Supplier<T> body);

    /** One namespace for everything: a command line, a library in a program of its own, a test. */
    static NamespaceProvider fixed(String namespace) {
        return new NamespaceProvider() {
            @Override
            public String current() {
                return namespace;
            }

            @Override
            public <T> T callIn(String ignored, Supplier<T> body) {
                return body.get();
            }
        };
    }
}
