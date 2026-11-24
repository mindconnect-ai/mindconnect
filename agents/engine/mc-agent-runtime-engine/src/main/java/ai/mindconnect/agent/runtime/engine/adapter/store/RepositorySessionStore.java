package ai.mindconnect.agent.runtime.engine.adapter.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.Scope;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.engine.port.out.SessionRepository;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageId;
import ai.mindconnect.message.port.out.MessageRepository;

/**
 * The runtime's session store, put together from two repositories: one for
 * the sessions, and the message repository for what was said in them — a
 * conversation per session, under the session's id. The message repository
 * is the one every product already has, in memory, on files and in
 * Postgres; the runtime keeps no messages of its own. Which two
 * repositories is for whoever assembles a runtime to say.
 */
public final class RepositorySessionStore implements SessionStore {

    /**
     * The key in a session's metadata that names the conversation its
     * messages are in, where that is not the one under the session's own
     * id: a session that continues a conversation begun elsewhere.
     */
    public static final String CONVERSATION_ID = "conversationId";

    /** The conversation in the message repository that holds a session's messages. */
    public static ConversationId conversationOf(Session session) {
        return SessionMessageMapper.conversationOf(session);
    }

    private static final PageRequest EVERYTHING = new PageRequest(0, Integer.MAX_VALUE);

    private final SessionRepository sessions;
    private final MessageRepository messages;
    private static final SessionMessageMapper READER = new SessionMessageMapper();

    private final SessionMessageMapper mapper = READER;

    public RepositorySessionStore(SessionRepository sessions, MessageRepository messages) {
        this.sessions = sessions;
        this.messages = messages;
    }

    /**
     * A stored message as the turn message it was written from; empty for
     * one no turn wrote. For whoever reads a conversation beside the runtime
     * and wants it in the runtime's terms.
     */
    public static Optional<TurnMessage> turnMessageOf(Message stored) {
        return READER.turnMessage(stored);
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
        return sessions.find(sessionId)
                .map(session -> messages.findByConversation(SessionMessageMapper.conversationOf(session), EVERYTHING))
                .orElse(List.of()).stream()
                .map(mapper::turnMessage)
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public List<TurnMessage> append(SessionId sessionId, List<TurnMessage> added) {
        Session session = sessions.find(sessionId).orElseThrow(() -> missing(sessionId));
        List<TurnMessage> stored = new ArrayList<>();
        for (TurnMessage message : added) {
            // The repository hands out the place in the conversation; the id it stores under is the message's id.
            String id = messages.append(SessionMessageMapper.conversationOf(session),
                    sequenceNumber -> mapper.message(session, message, sequenceNumber)).id().value();
            stored.add(message.stored(id));
        }
        return stored;
    }

    @Override
    public void updateMessageMetadata(SessionId sessionId, String messageId,
                                      UnaryOperator<Map<String, Object>> change) {
        Session session = sessions.find(sessionId).orElseThrow(() -> missing(sessionId));
        messages.update(SessionMessageMapper.conversationOf(session), MessageId.of(messageId),
                        stored -> mapper.turnMessage(stored)
                                .map(message -> mapper.withTurnMetadata(stored, change.apply(message.metadata())))
                                .orElse(stored))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Session " + sessionId.value() + " has no message " + messageId));
    }

    private static IllegalArgumentException missing(SessionId sessionId) {
        return new IllegalArgumentException("There is no session " + sessionId.value());
    }
}
