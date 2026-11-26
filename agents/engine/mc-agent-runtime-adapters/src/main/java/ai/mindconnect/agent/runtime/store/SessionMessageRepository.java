package ai.mindconnect.agent.runtime.store;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.StoredMessage;

/**
 * The messages of the sessions, each session's in the order they were
 * appended. A session's messages are the agent's working memory, not a
 * conversation people read, so they are kept apart from the message
 * repository.
 *
 * <p>Ids are kept: a message that comes with one keeps it, so moving a
 * session from another store leaves every reference to its messages intact.
 */
public interface SessionMessageRepository {

    /** The session's messages with their places, oldest first; empty for a session that has none. */
    List<StoredMessage> findBySession(SessionId session);

    /**
     * Appends the messages as one write and returns them as stored. A
     * message without an id is given one; each gets the next place, and a
     * place is never given twice.
     */
    List<StoredMessage> append(SessionId session, List<TurnMessage> messages);

    /**
     * Stores messages as another store kept them, each at its place and time,
     * for moving a session here. A place taken this way is not given again.
     */
    void restore(SessionId session, List<StoredMessage> messages);

    /** Replaces one message by what {@code change} makes of it; empty if the session has no such message. */
    Optional<StoredMessage> update(SessionId session, String messageId, UnaryOperator<TurnMessage> change);

    /** Removes the messages whose place lies between both, included; how many were removed. */
    int deleteRange(SessionId session, long from, long to);

    /** Removes every message of the session. */
    void deleteBySession(SessionId session);
}
