package ai.mindconnect.agent.runtime.capabilities.memory;

import ai.mindconnect.agent.SessionId;

/**
 * Summarises a session's earlier turns now, without waiting for a model
 * call to find the conversation too long. What a user asks for who wants
 * room before the next question.
 */
public interface MemoryCompaction {

    /**
     * Summarises everything before the session's last turn that no summary
     * covers yet. Nothing happens for a session whose agent does not keep a
     * summary, see {@link MemoryConfig.Strategy#COMPACT}.
     *
     * @return how many messages the summary now covers in addition
     * @throws IllegalArgumentException if there is no such session
     */
    int compact(SessionId sessionId);
}
