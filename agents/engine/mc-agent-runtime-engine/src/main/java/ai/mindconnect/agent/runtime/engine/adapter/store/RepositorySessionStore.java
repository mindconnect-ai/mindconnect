package ai.mindconnect.agent.runtime.engine.adapter.store;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.engine.port.out.SessionRepository;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.adapter.file.FileMessageRepository;
import ai.mindconnect.message.adapter.memory.InMemoryMessageRepository;
import ai.mindconnect.message.domain.MessageId;
import ai.mindconnect.message.port.out.MessageRepository;

/**
 * The runtime's session store, put together from two repositories: one for
 * the sessions, and the message repository for what was said in them — a
 * conversation per session, under the session's id. The message repository
 * is the one every product already has, in memory, on files and in
 * Postgres; the runtime keeps no messages of its own.
 */
public final class RepositorySessionStore implements SessionStore {

    private static final PageRequest EVERYTHING = new PageRequest(0, Integer.MAX_VALUE);

    private final SessionRepository sessions;
    private final MessageRepository messages;
    private final SessionMessageMapper mapper = new SessionMessageMapper();

    public RepositorySessionStore(SessionRepository sessions, MessageRepository messages) {
        this.sessions = sessions;
        this.messages = messages;
    }

    /** Everything in the memory of this process. */
    public static RepositorySessionStore inMemory(NamespaceProvider namespaces) {
        return new RepositorySessionStore(new InMemorySessionRepository(namespaces),
                new InMemoryMessageRepository(scopeOf(namespaces)));
    }

    /** Everything as files under one directory, a folder per namespace. */
    public static RepositorySessionStore onFiles(Path base, ObjectMapper json, NamespaceProvider namespaces) {
        return new RepositorySessionStore(new FileSessionRepository(base, json, namespaces),
                new FileMessageRepository(base, json, scopeOf(namespaces)));
    }

    /** The message repository asks a scope supplier where the runtime asks a namespace provider. */
    private static ScopeSupplier scopeOf(NamespaceProvider namespaces) {
        return () -> Scope.of(new Namespace(namespaces.current()));
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
