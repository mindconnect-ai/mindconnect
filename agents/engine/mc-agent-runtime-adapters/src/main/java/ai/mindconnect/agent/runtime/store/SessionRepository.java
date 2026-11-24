package ai.mindconnect.agent.runtime.store;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;

/**
 * Where the sessions themselves are kept — who it belongs to, its agent,
 * its metadata. Not its messages: those go to the message repository, and
 * a {@code SessionStore} is the two put together.
 */
public interface SessionRepository {

    /** @throws IllegalStateException if a session of that id exists already */
    Session create(Session session);

    Optional<Session> find(SessionId id);

    /**
     * Reads, changes and writes as one step: two callers that each change
     * something take turns instead of overwriting each other. Empty when
     * there is no such session.
     */
    Optional<Session> update(SessionId id, UnaryOperator<Session> change);

    /** A user's sessions that stand under no other, newest first. */
    List<Session> findByUser(UserId user);

    /** The sessions opened under this one — a sub-agent's, a fork taken as a child — oldest first. */
    List<Session> findByParent(SessionId parent);

    /** Removes the session and nothing else; whether there was one. */
    boolean delete(SessionId id);
}
