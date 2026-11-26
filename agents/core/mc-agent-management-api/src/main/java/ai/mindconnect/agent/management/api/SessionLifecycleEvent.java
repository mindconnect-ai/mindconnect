package ai.mindconnect.agent.management.api;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;

/**
 * What changed about a session, coarse enough for a list of sessions: it was
 * opened, renamed, deleted, or is busy, waiting or idle now. Whoever wants
 * more reads the session, or follows its stream.
 */
public sealed interface SessionLifecycleEvent {

    SessionId sessionId();

    /** A session was opened. {@code type} says what kind, so a list shows only the sessions it lists. */
    record Opened(SessionId sessionId, AgentId agentId, String type) implements SessionLifecycleEvent { }

    record Renamed(SessionId sessionId, String title) implements SessionLifecycleEvent { }

    /** A turn of the session began, stopped to ask or ended: what the session's status is now. */
    record StatusChanged(SessionId sessionId, ManagedSession.Status status) implements SessionLifecycleEvent { }

    record Deleted(SessionId sessionId) implements SessionLifecycleEvent { }
}
