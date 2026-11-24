package ai.mindconnect.agent.runtime.api.port.in;

import ai.mindconnect.agent.runtime.api.domain.SessionEvent;

/**
 * Hears the turns of sessions begin, ask and end. Few events, so it is the
 * listener for a list of sessions — a user's, or all of them.
 */
@FunctionalInterface
public interface SessionLifecycleListener {

    void onEvent(SessionEvent.Lifecycle event);
}
