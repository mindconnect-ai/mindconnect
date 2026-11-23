package ai.mindconnect.agent.runtime.api.port.out;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
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

    void save(Session session);

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
     * Appends the messages and returns them as stored, each with its id.
     * What comes in one call belongs together and is written together.
     */
    List<TurnMessage> append(SessionId sessionId, List<TurnMessage> messages);
}
