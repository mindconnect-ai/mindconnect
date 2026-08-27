package ai.mindconnect.agent.tools.workspace;

import java.util.UUID;

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
        UUID agentId,
        String userId,
        UUID sessionId
) {
    public enum ScopeType { USER, AGENT_USER, SESSION }

    public static WorkspaceScope user(String userId) {
        return new WorkspaceScope(ScopeType.USER, null, userId, null);
    }

    public static WorkspaceScope agentUser(UUID agentId, String userId) {
        return new WorkspaceScope(ScopeType.AGENT_USER, agentId, userId, null);
    }

    public static WorkspaceScope session(UUID agentId, String userId, UUID sessionId) {
        return new WorkspaceScope(ScopeType.SESSION, agentId, userId, sessionId);
    }
}
