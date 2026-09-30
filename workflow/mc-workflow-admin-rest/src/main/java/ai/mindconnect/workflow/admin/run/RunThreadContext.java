package ai.mindconnect.workflow.admin.run;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Carries whatever the host keeps on the request thread into the thread a
 * workflow run is handed to. The workflow area knows nothing about what
 * that is — a tenant, a user, a trace — it only promises to run the body
 * through the host's {@link #carry} when it moves work to another thread,
 * and through {@link #call} when the run stays on the request thread: what
 * the host adds for a run, such as the user its steps act for, is not on
 * the request thread by itself either.
 * A host that keeps nothing thread-bound leaves the default in place.
 */
@FunctionalInterface
public interface RunThreadContext {

    /** Nothing to carry: the body runs as it is. */
    RunThreadContext NONE = body -> body;

    /** The body wrapped so that it runs with the caller's thread context. Called on the caller's thread. */
    Runnable carry(Runnable body);

    /** Runs {@code body} right here, with the context {@link #carry} gives it, and returns its result. */
    default <T> T call(Supplier<T> body) {
        AtomicReference<T> result = new AtomicReference<>();
        carry(() -> result.set(body.get())).run();
        return result.get();
    }
}
