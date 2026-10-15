package ai.mindconnect.agent.runtime.port.out;

import ai.mindconnect.agent.SessionId;

/**
 * Drops what a feature keeps for a session when the session is deleted, so the
 * runtime deletes a session without knowing every store that holds a part of it.
 */
public interface SessionCleanup {

    /** Called while the session is deleted, before its own record goes. Idempotent. */
    void sessionDeleted(SessionId sessionId);
}
