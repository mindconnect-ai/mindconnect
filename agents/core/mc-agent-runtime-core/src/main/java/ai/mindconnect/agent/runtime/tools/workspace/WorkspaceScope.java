package ai.mindconnect.agent.runtime.tools.workspace;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.AgentId;

/**
 * Identifies one of the three logical workspace scopes for an agent session.
 *
 * <ul>
 *   <li>{@link ScopeType#USER} — cross-agent user profile (agentId is null)</li>
 *   <li>{@link ScopeType#AGENT_USER} — persistent memory per agent+user (sessionId is null)</li>
 *   <li>{@link ScopeType#SESSION} — session scratch space (all three IDs present)</li>
 * </ul>
 */
public record WorkspaceScope(
        ScopeType type,
        AgentId agentId,
        UserId userId,
        SessionId sessionId
) {
    public enum ScopeType { USER, AGENT_USER, SESSION }

    public static WorkspaceScope user(UserId userId) {
        return new WorkspaceScope(ScopeType.USER, null, userId, null);
    }

    public static WorkspaceScope agentUser(AgentId agentId, UserId userId) {
        return new WorkspaceScope(ScopeType.AGENT_USER, agentId, userId, null);
    }

    public static WorkspaceScope session(AgentId agentId, UserId userId, SessionId sessionId) {
        return new WorkspaceScope(ScopeType.SESSION, agentId, userId, sessionId);
    }
}
