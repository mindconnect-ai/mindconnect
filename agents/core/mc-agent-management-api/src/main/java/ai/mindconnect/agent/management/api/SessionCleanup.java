package ai.mindconnect.agent.management.api;

/**
 * What goes with a session when it is deleted, beyond the session and what
 * was said in it. Whoever keeps something per session — summaries, traces,
 * a directory — contributes one, so that deleting a session is one call for
 * every caller and leaves nothing behind.
 */
public interface SessionCleanup {

    /** The session is gone, with its messages; called for every session under it as well. */
    void deleted(ManagedSession session);
}
