package ai.mindconnect.agent.management.api;

import java.util.List;
import java.util.Optional;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.UserId;

/**
 * What is done with sessions beside running turns in them. A session is
 * opened and worked in through the runtime's {@code SessionService} and
 * {@code TurnService}; it is listed, renamed, moved and deleted here.
 *
 * <p>Works in the namespace of whoever calls, like the runtime. Whether a
 * caller may touch a session is the caller's to check: this API knows
 * owners, not permissions.
 */
public interface SessionManagement {

    /** A user's sessions that stand under no other, newest first. */
    List<ManagedSession> list(UserId user);

    /**
     * Opens a session for a user.
     *
     * @param agent       the agent it runs on: a managed agent it refers
     *                    to, or a definition of its own
     * @param type        what kind of session it is; {@code null} for
     *                    {@link ManagedSession#CHAT}
     * @param directories where its tools work. Each has to pass the
     *                    installation's {@link SessionDirectoryPolicy};
     *                    without a working directory the session gets the
     *                    one the policy assigns
     * @throws IllegalArgumentException for a managed agent that does not
     *         exist, or a directory the policy refuses
     */
    ManagedSession open(UserId user, SessionAgent agent, String type, SessionDirectories directories);

    /** The sessions under this one — its sub-agents' and its forks taken as children — oldest first. */
    List<ManagedSession> children(SessionId sessionId);

    Optional<ManagedSession> find(SessionId sessionId);

    /** @throws IllegalArgumentException here and below, if there is no such session */
    ManagedSession rename(SessionId sessionId, String title);

    ManagedSession changeStatus(SessionId sessionId, ManagedSession.Status status);

    /**
     * Other directories for the session. A directory it does not have yet
     * has to pass the installation's {@link SessionDirectoryPolicy}; one it
     * has stays without being asked about again, and the directory it was
     * given is kept whatever is named here.
     *
     * @throws IllegalArgumentException also for a directory the policy refuses
     */
    ManagedSession changeDirectories(SessionId sessionId, SessionDirectories directories);

    /**
     * Sets one entry of the session's metadata. For what a client keeps
     * about a session under a key of its own; what has a method here — the
     * title, the status, the directories — is changed through that method.
     */
    ManagedSession setMetadata(SessionId sessionId, String key, Object value);

    /** Attaches a file to the session; a file of the same name it already holds is replaced. */
    ManagedSession attach(SessionId sessionId, SessionAttachment file);

    /** Takes the file of that name from the session; nothing changes if it holds none. */
    ManagedSession detach(SessionId sessionId, String fileName);

    /**
     * A definition of the session's own to run on; what was said stays. A
     * session that referred to a managed agent stops following it and keeps
     * it as where the definition came from.
     */
    ManagedSession changeAgent(SessionId sessionId, AgentDefinition agent);

    /**
     * The session runs on this managed agent from now on, as that agent
     * stands when a turn begins.
     *
     * @throws IllegalArgumentException also if there is no such agent
     */
    ManagedSession useAgent(SessionId sessionId, AgentId agent);

    /**
     * The definition a turn in this session runs on now: the managed agent's
     * for a session that refers to one — as last seen, should that agent be
     * gone — else the session's own. Empty for a session without an agent.
     */
    Optional<AgentDefinition> agentOf(SessionId sessionId);

    /**
     * Deletes the session with what was said in it and every session under
     * it, and what the installation's {@link SessionCleanup}s keep of them.
     * Nothing happens if there is no such session.
     */
    void delete(SessionId sessionId);

    /** What was said in the session, oldest first. */
    List<ManagedMessage> messages(SessionId sessionId);

    /**
     * Deletes the message at {@code sequence} and everything after it: how
     * an answer is thrown away to be given again.
     */
    void deleteMessagesFrom(SessionId sessionId, int sequence);
}
