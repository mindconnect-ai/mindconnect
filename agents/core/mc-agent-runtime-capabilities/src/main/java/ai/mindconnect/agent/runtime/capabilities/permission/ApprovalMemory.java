package ai.mindconnect.agent.runtime.capabilities.permission;

import ai.mindconnect.agent.runtime.api.domain.SessionId;

/** The approvals a user gave "for the rest of this session", so the same tool does not ask twice. */
public interface ApprovalMemory {

    boolean approved(SessionId sessionId, String toolName);

    void remember(SessionId sessionId, String toolName);
}
