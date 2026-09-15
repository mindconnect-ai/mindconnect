package ai.mindconnect.workflow.admin.run;

/**
 * Carries whatever the host keeps on the request thread into the thread a
 * workflow run is handed to. The workflow area knows nothing about what
 * that is — a tenant, a user, a trace — it only promises to run the body
 * through the host's {@link #carry} when it moves work to another thread.
 * A host that keeps nothing thread-bound leaves the default in place.
 */
@FunctionalInterface
public interface RunThreadContext {

    /** Nothing to carry: the body runs as it is. */
    RunThreadContext NONE = body -> body;

    /** The body wrapped so that it runs with the caller's thread context. Called on the caller's thread. */
    Runnable carry(Runnable body);
}
