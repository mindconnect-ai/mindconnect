package ai.mindconnect.agent.runtime.capabilities.memory;

import ai.mindconnect.agent.SessionId;

/**
 * Shows what a session's agent would be sent now. It only looks: nothing is
 * summarised and nothing is kept, so asking twice gives the same answer and
 * the next turn finds the session as it was.
 */
public interface MemoryInspection {

    /** @throws IllegalArgumentException if there is no such session or it has no agent */
    WorkingMemory inspect(SessionId sessionId);
}
