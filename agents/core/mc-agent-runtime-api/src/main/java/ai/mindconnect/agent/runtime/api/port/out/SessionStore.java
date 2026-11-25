package ai.mindconnect.agent.runtime.api.port.out;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.StoredMessage;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.TurnId;

/**
 * Where sessions are kept, each with its messages — a session is the
 * conversation that can be continued, so the two are stored as one.
 *
 * <p>Every message belongs to the turn that wrote it and says so in its
 * {@code turnId}. That and the metadata go in and come back unchanged: the
 * agent loop reads a turn's open tool calls, its rounds and their cost from
 * them.
 */
public interface SessionStore {

    /**
     * The metadata key of a message the model is no longer shown in full:
     * its value is the short text shown in its place. A store with a field
     * of its own for that keeps it there.
     */
    String COMPRESSED = "compressed";

    /**
     * Keeps a new session.
     *
     * @throws IllegalStateException if a session of that id exists already
     */
    void create(Session session);

    Optional<Session> find(SessionId sessionId);

    /**
     * Changes a session where it is kept and returns it as it then stands.
     * The store reads, applies the change and writes as one step, so two
     * plugins that each add something to the metadata do not overwrite each
     * other — which reading and saving on their own would.
     *
     * @throws IllegalArgumentException if there is no such session
     */
    Session update(SessionId sessionId, UnaryOperator<Session> change);

    /** The session's messages, oldest first. */
    List<TurnMessage> messages(SessionId sessionId);

    /**
     * What one turn wrote into the session, oldest first: the user's
     * message that opened it, its tool calls and results, its answer. A
     * store that can look a turn up directly answers from there instead of
     * reading the whole session.
     */
    default List<TurnMessage> messages(SessionId sessionId, TurnId turnId) {
        return messages(sessionId).stream()
                .filter(message -> turnId.value().equals(message.turnId()))
                .toList();
    }

    /**
     * The session's messages with their place in it, oldest first. A store
     * that keeps places of its own answers with those; this one counts.
     */
    default List<StoredMessage> history(SessionId sessionId) {
        List<TurnMessage> all = messages(sessionId);
        return IntStream.range(0, all.size())
                .mapToObj(index -> new StoredMessage(all.get(index), index + 1, null))
                .toList();
    }

    /**
     * Removes the messages whose place lies between {@code from} and
     * {@code to}, both included — what undoing the end of a session means.
     *
     * @return how many were removed
     */
    default int deleteMessages(SessionId sessionId, long from, long to) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " removes no messages");
    }

    /** Removes every message of the session, before the session itself goes. */
    default void deleteMessages(SessionId sessionId) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " removes no messages");
    }

    /**
     * Appends the messages and returns them as stored, each with its id.
     * What comes in one call belongs together and is written together.
     */
    List<TurnMessage> append(SessionId sessionId, List<TurnMessage> messages);

    /**
     * Changes the metadata of one stored message, as one step. What a
     * message says is never changed: a session keeps everything as it was
     * said, and whoever wants the model to read something else in its place
     * says so here — see {@link #COMPRESSED}.
     *
     * @throws IllegalArgumentException if the session has no such message
     */
    void updateMessageMetadata(SessionId sessionId, String messageId,
                               UnaryOperator<Map<String, Object>> change);
}
