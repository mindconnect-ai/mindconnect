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

    /** Another agent for the turns that bring none of their own; what was said stays. */
    ManagedSession changeAgent(SessionId sessionId, AgentDefinition agent);

    /** Deletes the session, what was said in it, and every session under it. A session that is not there is none. */
    void delete(SessionId sessionId);

    /** What was said in the session, oldest first. */
    List<ManagedMessage> messages(SessionId sessionId);

    /**
     * Deletes the message at {@code sequence} and everything after it: how
     * an answer is thrown away to be given again.
     */
    void deleteMessagesFrom(SessionId sessionId, int sequence);
}
