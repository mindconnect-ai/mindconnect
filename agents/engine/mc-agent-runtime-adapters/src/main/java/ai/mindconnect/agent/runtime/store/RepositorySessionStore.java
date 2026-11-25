package ai.mindconnect.agent.runtime.store;

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
import ai.mindconnect.agent.runtime.api.domain.StoredMessage;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.store.SessionRepository;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.jackson.JsonMapperFactory;
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

    /**
     * The conversation in the message repository that holds a session's
     * messages: the one its metadata names, else the one under its own id.
     * A sub-agent's session is opened with its caller's metadata, this key
     * with it; one that names its caller's conversation keeps its messages
     * under its own id, out of the caller's history. One that names another
     * conversation has one of its own — the earlier runtime gave its
     * sub-agents' sessions that.
     */
    ConversationId conversationOf(Session session) {
        if (!(session.metadata().get(CONVERSATION_ID) instanceof String named)) {
            return ConversationId.of(session.id().value());
        }
        boolean inherited = session.parentId() != null && sessions.find(session.parentId())
                .map(parent -> conversationOf(parent).value().equals(named))
                .orElse(false);
        return ConversationId.of(inherited ? session.id().value() : named);
    }

    private static final PageRequest EVERYTHING = new PageRequest(0, Integer.MAX_VALUE);

    private final SessionRepository sessions;
    private final MessageRepository messages;
    private final SessionMessageMapper mapper;

    /** @param json where the mapper comes from that writes and reads a tool call's or result's content */
    public RepositorySessionStore(SessionRepository sessions, MessageRepository messages, JsonMapperFactory json) {
        this.sessions = sessions;
        this.messages = messages;
        this.mapper = new SessionMessageMapper(json);
    }

    /**
     * A stored message as the turn message it was written from; empty for
     * one no turn wrote. For whoever reads a conversation beside the runtime
     * and wants it in the runtime's terms.
     */
    public Optional<TurnMessage> turnMessageOf(Message stored) {
        return mapper.turnMessage(stored);
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
                .map(session -> messages.findByConversation(conversationOf(session), EVERYTHING))
                .orElse(List.of()).stream()
                .map(mapper::turnMessage)
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public List<StoredMessage> history(SessionId sessionId) {
        return sessions.find(sessionId)
                .map(session -> conversation(conversationOf(session)))
                .orElse(List.of());
    }

    /**
     * A conversation's messages with their places, read by its id whatever
     * session names it: for moving what the earlier runtime kept, whose
     * sub-agents' sessions had conversations of their own.
     */
    public List<StoredMessage> conversation(ConversationId conversation) {
        return messages.findByConversation(conversation, EVERYTHING).stream()
                .flatMap(stored -> mapper.turnMessage(stored).stream()
                        .map(message -> new StoredMessage(message, stored.sequenceNum(), stored.sentAt())))
                .toList();
    }

    @Override
    public int deleteMessages(SessionId sessionId, long from, long to) {
        Session session = sessions.find(sessionId).orElseThrow(() -> missing(sessionId));
        ConversationId conversation = conversationOf(session);
        // The message repository counts places in int; a place beyond that is beyond every message.
        int first = (int) Math.min(from, Integer.MAX_VALUE);
        int last = (int) Math.min(to, Integer.MAX_VALUE);
        int removed = (int) messages.findByConversation(conversation, EVERYTHING).stream()
                .filter(message -> message.sequenceNum() >= first && message.sequenceNum() <= last)
                .count();
        messages.deleteBySequenceRange(conversation, first, last);
        return removed;
    }

    @Override
    public void deleteMessages(SessionId sessionId) {
        sessions.find(sessionId).ifPresent(session ->
                messages.deleteByConversation(conversationOf(session)));
    }

    @Override
    public List<TurnMessage> append(SessionId sessionId, List<TurnMessage> added) {
        Session session = sessions.find(sessionId).orElseThrow(() -> missing(sessionId));
        ConversationId conversation = conversationOf(session);
        List<TurnMessage> stored = new ArrayList<>();
        for (TurnMessage message : added) {
            // The repository hands out the place in the conversation; the id it stores under is the message's id.
            String id = messages.append(conversation,
                    sequenceNumber -> mapper.message(conversation, session, message, sequenceNumber)).id().value();
            stored.add(message.stored(id));
        }
        return stored;
    }

    @Override
    public void updateMessageMetadata(SessionId sessionId, String messageId,
                                      UnaryOperator<Map<String, Object>> change) {
        Session session = sessions.find(sessionId).orElseThrow(() -> missing(sessionId));
        messages.update(conversationOf(session), MessageId.of(messageId),
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
