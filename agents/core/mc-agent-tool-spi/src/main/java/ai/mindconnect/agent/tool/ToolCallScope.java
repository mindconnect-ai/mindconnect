package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;

import java.util.List;

/**
 * Per-invocation context passed to a {@link ToolFactory} when resolving a
 * concrete {@link Tool} for one agent call: whose call it is, in which
 * session and agent it happens, and where on disk that session works.
 *
 * <p>Session and agent are optional — a tool resolved for the catalog or a
 * test bench has neither.
 *
 * @param userId         who is chatting; may be null for the installation's own runs
 * @param sessionId      the session; null when a tool is resolved outside one
 * @param agentId        the agent whose binding is resolved; null under the same conditions
 * @param rootSessionId  the chat at the top of the session's sub-agent chain — the one the
 *                       user sees and attaches files to; the session itself when it is no
 *                       sub-agent's, null without a session
 * @param workingDir     the session's working directory — the directory the
 *                       user is "in", as an absolute path — or {@code null}
 *                       when the session has none. A file-rooted tool treats
 *                       it as its base directory ahead of any configured
 *                       default.
 * @param additionalDirs further directories the session may reach by
 *                       absolute path, beside the working directory; empty
 *                       when none.
 */
public record ToolCallScope(
        UserId userId,
        SessionId sessionId,
        AgentId agentId,
        SessionId rootSessionId,
        String workingDir,
        List<String> additionalDirs
) {

    public ToolCallScope {
        additionalDirs = additionalDirs == null ? List.of() : List.copyOf(additionalDirs);
    }

    /** A scope whose session is its own root, with no working directory. */
    public ToolCallScope(UserId userId, SessionId sessionId, AgentId agentId) {
        this(userId, sessionId, agentId, sessionId, null, List.of());
    }

    /** A scope whose session is its own root, working in {@code workingDir}. */
    public ToolCallScope(UserId userId, SessionId sessionId, AgentId agentId,
                         String workingDir, List<String> additionalDirs) {
        this(userId, sessionId, agentId, sessionId, workingDir, additionalDirs);
    }

    /** A scope with no session and no agent — resolving a tool to look at it, not to run it. */
    public static ToolCallScope detached(UserId userId) {
        return new ToolCallScope(userId, null, null);
    }

    /** The session's part of a scope — what a registry fills the agent in on per tool. */
    public static ToolCallScope ofSession(UserId userId, SessionId sessionId, String workingDir) {
        return new ToolCallScope(userId, sessionId, null, workingDir, List.of());
    }

    /** Same, with the session's additional directories. */
    public static ToolCallScope ofSession(UserId userId, SessionId sessionId,
                                          String workingDir, List<String> additionalDirs) {
        return new ToolCallScope(userId, sessionId, null, workingDir, additionalDirs);
    }

    /** This scope for one agent's tool. */
    public ToolCallScope forAgent(AgentId agentId) {
        return new ToolCallScope(userId, sessionId, agentId, rootSessionId, workingDir, additionalDirs);
    }

    public boolean inSession() {
        return sessionId != null;
    }

    /** Has the session a working directory? */
    public boolean hasWorkingDir() {
        return workingDir != null && !workingDir.isBlank();
    }

    /**
     * Where a file-rooted tool may go in this scope: the working directory
     * as the base when the session has one, else {@code fallbackBase}; the
     * additional directories either way.
     */
    public FileRoots fileRoots(String fallbackBase) {
        return FileRoots.of(hasWorkingDir() ? workingDir : fallbackBase, additionalDirs);
    }
}
