package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;

/**
 * Per-invocation context passed to a {@link ToolFactory} when resolving a
 * concrete {@link Tool} for one agent call: whose call it is, and in which
 * session and agent it happens.
 *
 * <p>Session and agent are optional — a tool resolved for the catalog or a
 * test bench has neither.
 *
 * @param userId        who is chatting; may be null for the installation's own runs
 * @param sessionId     the session; null when a tool is resolved outside one
 * @param agentId       the agent whose binding is resolved; null under the same conditions
 * @param rootSessionId the chat at the top of the session's sub-agent chain — the one the
 *                      user sees and attaches files to; the session itself when it is no
 *                      sub-agent's, null without a session
 */
public record ToolCallScope(
        UserId userId,
        SessionId sessionId,
        AgentId agentId,
        SessionId rootSessionId
) {

    /** A scope whose session is its own root: a top-level chat, or no session at all. */
    public ToolCallScope(UserId userId, SessionId sessionId, AgentId agentId) {
        this(userId, sessionId, agentId, sessionId);
    }

    /** A scope with no session and no agent — resolving a tool to look at it, not to run it. */
    public static ToolCallScope detached(UserId userId) {
        return new ToolCallScope(userId, null, null);
    }

    public boolean inSession() {
        return sessionId != null;
    }
}
