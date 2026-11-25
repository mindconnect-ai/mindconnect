package ai.mindconnect.agent.runtime.api.port.out;

import ai.mindconnect.agent.runtime.api.domain.SessionEvent;

/**
 * Lets a capability say something to whoever follows a session. The engine
 * tells of turns, messages and tool calls itself; this is for what only a
 * capability knows, as an event of a kind of its own.
 */
public interface SessionEventPublisher {

    /** Reaches the listeners of the event's session. An event for a session that does not exist goes nowhere. */
    void publish(SessionEvent event);
}
