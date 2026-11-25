package ai.mindconnect.agent.runtime.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.StoredMessage;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;

/**
 * The runtime's session store, put together from the sessions and their
 * messages, each in a repository of its own. It knows no conversation and no
 * message repository: a session's messages are kept as the turns wrote them.
 */
public final class DefaultSessionStore implements SessionStore {

    private final SessionRepository sessions;
    private final SessionMessageRepository messages;

    public DefaultSessionStore(SessionRepository sessions, SessionMessageRepository messages) {
        this.sessions = sessions;
        this.messages = messages;
    }

    @Override
    public void create(Session session) {
        sessions.create(session);
    }

    @Override
    public Optional<Session> find(SessionId sessionId) {
        return sessions.find(sessionId);
    }

    @Override
    public Session update(SessionId sessionId, UnaryOperator<Session> change) {
        return sessions.update(sessionId, change).orElseThrow(() -> missing(sessionId));
    }

    @Override
    public List<TurnMessage> messages(SessionId sessionId) {
        return history(sessionId).stream().map(StoredMessage::message).toList();
    }

    @Override
    public List<StoredMessage> history(SessionId sessionId) {
        return messages.findBySession(sessionId);
    }

    @Override
    public List<TurnMessage> append(SessionId sessionId, List<TurnMessage> added) {
        existing(sessionId);
        return messages.append(sessionId, added).stream().map(StoredMessage::message).toList();
    }

    @Override
    public void updateMessageMetadata(SessionId sessionId, String messageId,
                                      UnaryOperator<Map<String, Object>> change) {
        existing(sessionId);
        messages.update(sessionId, messageId, message -> new TurnMessage(message.id(), message.turnId(),
                        message.type(), message.content(), message.toolCalls(), change.apply(message.metadata())))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Session " + sessionId.value() + " has no message " + messageId));
    }

    @Override
    public int deleteMessages(SessionId sessionId, long from, long to) {
        existing(sessionId);
        return messages.deleteRange(sessionId, from, to);
    }

    @Override
    public void deleteMessages(SessionId sessionId) {
        messages.deleteBySession(sessionId);
    }

    private void existing(SessionId sessionId) {
        sessions.find(sessionId).orElseThrow(() -> missing(sessionId));
    }

    private static IllegalArgumentException missing(SessionId sessionId) {
        return new IllegalArgumentException("There is no session " + sessionId.value());
    }
}
