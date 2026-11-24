package ai.mindconnect.agent.runtime.api.port.in;

import ai.mindconnect.agent.runtime.api.domain.SessionEvent;

/**
 * Hears a session as it happens: every message, every tool call, every
 * fragment of an answer. Many events, so it is the listener for a session
 * that is shown, never for a list of them.
 */
@FunctionalInterface
public interface SessionStreamListener {

    void onEvent(SessionEvent.Stream event);
}
