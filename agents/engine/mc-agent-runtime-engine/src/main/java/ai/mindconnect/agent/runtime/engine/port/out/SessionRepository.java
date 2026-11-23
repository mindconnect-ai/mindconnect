package ai.mindconnect.agent.runtime.engine.port.out;

import java.util.Optional;
import java.util.function.UnaryOperator;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionId;

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
}
