package ai.mindconnect.agent.management.api;

/** Who hears what changes about a user's sessions. Told on a thread of its own, in the order things happened. */
@FunctionalInterface
public interface SessionLifecycleListener {

    void onEvent(SessionLifecycleEvent event);
}
