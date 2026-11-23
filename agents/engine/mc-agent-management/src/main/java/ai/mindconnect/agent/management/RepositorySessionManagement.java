package ai.mindconnect.agent.management;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import ai.mindconnect.agent.management.api.ManagedMessage;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionDirectories;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.UserId;
import ai.mindconnect.agent.runtime.engine.adapter.store.RepositorySessionStore;
import ai.mindconnect.agent.runtime.engine.port.out.SessionRepository;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.port.out.MessageRepository;

/**
 * Session management on the two repositories the engine's session store is
 * put together from: the sessions in the engine's own, what was said in the
 * message repository. It reads and writes what the runtime reads and writes,
 * so a session renamed here is the session a turn finds.
 */
public final class RepositorySessionManagement implements SessionManagement {

    private static final PageRequest EVERYTHING = new PageRequest(0, Integer.MAX_VALUE);

    private final SessionRepository sessions;
    private final MessageRepository messages;

    public RepositorySessionManagement(SessionRepository sessions, MessageRepository messages) {
        this.sessions = sessions;
        this.messages = messages;
    }

    @Override
    public List<ManagedSession> list(UserId user) {
        return sessions.findByUser(user).stream().map(RepositorySessionManagement::managed).toList();
    }

    @Override
    public List<ManagedSession> children(SessionId sessionId) {
        return sessions.findByParent(sessionId).stream().map(RepositorySessionManagement::managed).toList();
    }

    @Override
    public Optional<ManagedSession> find(SessionId sessionId) {
        return sessions.find(sessionId).map(RepositorySessionManagement::managed);
    }

    @Override
    public ManagedSession rename(SessionId sessionId, String title) {
        return changed(sessionId, session -> session.withMetadata(ManagedSession.TITLE, title));
    }

    @Override
    public ManagedSession changeStatus(SessionId sessionId, ManagedSession.Status status) {
        return changed(sessionId, session -> session.withMetadata(ManagedSession.STATUS, status.name()));
    }

    @Override
    public ManagedSession changeDirectories(SessionId sessionId, SessionDirectories directories) {
        return changed(sessionId, session -> {
            Session moved = session.withMetadata(ManagedSession.ADDITIONAL_DIRECTORIES, directories.additional());
            // No working directory is no entry: an entry cannot hold nothing.
            return directories.working() == null ? moved
                    : moved.withMetadata(ManagedSession.WORKING_DIRECTORY, directories.working());
        });
    }

    @Override
    public ManagedSession changeAgent(SessionId sessionId, AgentDefinition agent) {
        return changed(sessionId, session -> session.withAgent(agent));
    }

    @Override
    public void delete(SessionId sessionId) {
        sessions.findByParent(sessionId).forEach(child -> delete(child.id()));
        sessions.find(sessionId).ifPresent(session -> {
            messages.deleteByConversation(RepositorySessionStore.conversationOf(session));
            sessions.delete(sessionId);
        });
    }

    @Override
    public List<ManagedMessage> messages(SessionId sessionId) {
        return messages.findByConversation(RepositorySessionStore.conversationOf(existing(sessionId)), EVERYTHING)
                .stream()
                .flatMap(stored -> RepositorySessionStore.turnMessageOf(stored).stream()
                        .map(message -> new ManagedMessage(stored.id().value(), stored.sequenceNum(),
                                stored.sentAt(), message, stored.durationMs(), stored.tokenCount())))
                .toList();
    }

    @Override
    public void deleteMessagesFrom(SessionId sessionId, int sequence) {
        messages.deleteBySequenceRange(RepositorySessionStore.conversationOf(existing(sessionId)),
                sequence, Integer.MAX_VALUE);
    }

    private ManagedSession changed(SessionId sessionId, UnaryOperator<Session> change) {
        return sessions.update(sessionId, change).map(RepositorySessionManagement::managed)
                .orElseThrow(() -> missing(sessionId));
    }

    private Session existing(SessionId sessionId) {
        return sessions.find(sessionId).orElseThrow(() -> missing(sessionId));
    }

    private static IllegalArgumentException missing(SessionId sessionId) {
        return new IllegalArgumentException("There is no session " + sessionId.value());
    }

    private static ManagedSession managed(Session session) {
        return new ManagedSession(
                session.id(),
                session.userId(),
                session.parentId(),
                session.agent() == null ? null : session.agent().name(),
                session.metadata().get(ManagedSession.TITLE) instanceof String title ? title : null,
                session.metadata().get(ManagedSession.TYPE) instanceof String type ? type : ManagedSession.CHAT,
                session.metadata().get(ManagedSession.STATUS) instanceof String status
                        ? ManagedSession.Status.valueOf(status) : ManagedSession.Status.ACTIVE,
                new SessionDirectories(
                        session.metadata().get(ManagedSession.WORKING_DIRECTORY) instanceof String working
                                ? working : null,
                        session.metadata().get(ManagedSession.ADDITIONAL_DIRECTORIES) instanceof List<?> more
                                ? more.stream().map(String::valueOf).toList() : List.of()),
                session.createdAt());
    }
}
