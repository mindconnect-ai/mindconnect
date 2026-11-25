package ai.mindconnect.agent.management;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.ManagedMessage;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionAgent;
import ai.mindconnect.agent.management.api.SessionAttachment;
import ai.mindconnect.agent.management.api.SessionCleanup;
import ai.mindconnect.agent.management.api.SessionDirectories;
import ai.mindconnect.agent.management.api.SessionDirectoryPolicy;
import ai.mindconnect.agent.management.api.SessionLifecycleEvent;
import ai.mindconnect.agent.management.api.SessionLifecycleListener;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.store.RepositorySessionStore;
import ai.mindconnect.agent.runtime.store.SessionRepository;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.port.out.MessageRepository;

/**
 * Session management on the two repositories the engine's session store is
 * put together from: the sessions in the engine's own, what was said in the
 * message repository. It reads and writes what the runtime reads and writes,
 * so a session renamed here is the session a turn finds. A session that
 * refers to a managed agent finds it in the agent repository.
 */
public final class RepositorySessionManagement implements SessionManagement {

    private static final PageRequest EVERYTHING = new PageRequest(0, Integer.MAX_VALUE);

    private final SessionRepository sessions;
    private final MessageRepository messages;
    private final AgentRepository agents;
    private final SessionDirectoryPolicy directoryPolicy;
    private final List<SessionCleanup> cleanups;
    private final SessionLifecycle lifecycle;

    /** @param lifecycle where a session's status is read from and its users are told what changes */
    public RepositorySessionManagement(SessionRepository sessions, MessageRepository messages,
                                       AgentRepository agents, SessionDirectoryPolicy directoryPolicy,
                                       List<SessionCleanup> cleanups, SessionLifecycle lifecycle) {
        this.cleanups = List.copyOf(cleanups);
        this.lifecycle = lifecycle;
        this.sessions = sessions;
        this.messages = messages;
        this.agents = agents;
        this.directoryPolicy = directoryPolicy;
    }

    @Override
    public ManagedSession open(UserId user, SessionId parent, SessionAgent agent, String type,
                               SessionDirectories directories) {
        SessionId id = SessionId.of(UUID.randomUUID().toString());
        // Where the session above it works was admitted when that one was given it.
        Set<String> held = new HashSet<>();
        if (parent != null) {
            ManagedSession above = managed(existing(parent));
            held.addAll(above.directories().additional());
            held.add(above.directories().working());
            directoryPolicy.ownDirectory(above).ifPresent(held::add);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ManagedSession.TYPE, type == null ? ManagedSession.CHAT : type);
        AgentDefinition definition;
        if (agent instanceof SessionAgent.Ref ref) {
            definition = agents.find(ref.id()).map(ManagedAgent::definition).orElseThrow(() ->
                    new IllegalArgumentException("There is no agent " + ref.id().value()));
            metadata.put(ManagedSession.AGENT_ID, ref.id().value());
            metadata.put(ManagedSession.AGENT, ManagedSession.REF);
        } else {
            SessionAgent.Own own = (SessionAgent.Own) agent;
            definition = own.definition();
            metadata.put(ManagedSession.AGENT, ManagedSession.OWN);
            if (own.origin() != null) {
                metadata.put(ManagedSession.AGENT_ID, own.origin().value());
            }
        }
        String named = directories.working();
        Optional<String> working = named == null || named.isBlank()
                ? directoryPolicy.assign(user, id)
                : Optional.of(held.contains(named) ? named : directoryPolicy.admit(user, named));
        working.ifPresent(directory -> metadata.put(ManagedSession.WORKING_DIRECTORY, directory));
        metadata.put(ManagedSession.ADDITIONAL_DIRECTORIES, directories.additional().stream()
                .filter(directory -> !directory.isBlank())
                .map(directory -> held.contains(directory) ? directory : directoryPolicy.admit(user, directory))
                .distinct().toList());
        Session session = new Session(id, user, parent, definition, metadata, Instant.now());
        sessions.create(session);
        ManagedSession opened = managed(session);
        // A session opened under another one is its sub-agent's or its fork's: no list of the user's grows by it.
        if (parent == null) {
            lifecycle.tell(user, new SessionLifecycleEvent.Opened(id, opened.agentId().orElse(null), opened.type()));
        }
        return opened;
    }

    @Override
    public List<ManagedSession> list(UserId user) {
        return sessions.findByUser(user).stream().map(this::managed).toList();
    }

    @Override
    public List<ManagedSession> children(SessionId sessionId) {
        return sessions.findByParent(sessionId).stream().map(this::managed).toList();
    }

    @Override
    public Optional<ManagedSession> find(SessionId sessionId) {
        return sessions.find(sessionId).map(this::managed);
    }

    @Override
    public ManagedSession rename(SessionId sessionId, String title) {
        ManagedSession renamed = changed(sessionId, session -> session.withMetadata(ManagedSession.TITLE, title));
        lifecycle.tell(renamed.userId(), new SessionLifecycleEvent.Renamed(sessionId, title));
        return renamed;
    }

    @Override
    public ManagedSession changeDirectories(SessionId sessionId, SessionDirectories directories) {
        ManagedSession current = managed(existing(sessionId));
        Optional<String> own = directoryPolicy.ownDirectory(current);
        // What the session has passed once, or never had to: removing one directory resends the rest.
        Set<String> held = new HashSet<>(current.directories().additional());
        held.add(current.directories().working());
        own.ifPresent(held::add);

        String working = admitted(current, directories.working(), held);
        Set<String> additional = new LinkedHashSet<>();
        for (String directory : directories.additional()) {
            String admitted = admitted(current, directory, held);
            if (admitted != null) {
                additional.add(admitted);
            }
        }
        own.filter(directory -> !directory.equals(working)).ifPresent(additional::add);

        return changed(sessionId, session -> {
            Session moved = session.withMetadata(ManagedSession.ADDITIONAL_DIRECTORIES, List.copyOf(additional));
            // No working directory is no entry: an entry cannot hold nothing.
            return working == null ? moved.withoutMetadata(ManagedSession.WORKING_DIRECTORY)
                    : moved.withMetadata(ManagedSession.WORKING_DIRECTORY, working);
        });
    }

    /** The directory as it is stored: one the session has as it is, a new one as the policy admits it; none for blank. */
    private String admitted(ManagedSession session, String directory, Set<String> held) {
        if (directory == null || directory.isBlank()) {
            return null;
        }
        return held.contains(directory) ? directory : directoryPolicy.admit(session.userId(), directory);
    }

    @Override
    public ManagedSession attach(SessionId sessionId, SessionAttachment file) {
        return changed(sessionId, session -> {
            List<Object> attached = new ArrayList<>(without(session, file.name()));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", file.id());
            entry.put("name", file.name());
            entry.put("mediaType", file.mediaType());
            entry.put("sizeBytes", file.sizeBytes());
            entry.put("path", file.path());
            // An entry cannot hold nothing: a file without an id, a media type or a path has no such field.
            entry.values().removeIf(java.util.Objects::isNull);
            attached.add(entry);
            return session.withMetadata(ManagedSession.ATTACHMENTS, attached);
        });
    }

    @Override
    public ManagedSession detach(SessionId sessionId, String fileName) {
        return changed(sessionId, session ->
                session.withMetadata(ManagedSession.ATTACHMENTS, without(session, fileName)));
    }

    /** The session's attached files as they are stored, leaving out the one of that name. */
    private static List<?> without(Session session, String fileName) {
        return session.metadata().get(ManagedSession.ATTACHMENTS) instanceof List<?> attached
                ? attached.stream()
                        .filter(entry -> !(entry instanceof Map<?, ?> file && fileName.equals(file.get("name"))))
                        .toList()
                : List.of();
    }

    @Override
    public ManagedSession setMetadata(SessionId sessionId, String key, Object value) {
        return changed(sessionId, session -> session.withMetadata(key, value));
    }

    @Override
    public ManagedSession changeAgent(SessionId sessionId, AgentDefinition agent) {
        return changed(sessionId, session -> session.withAgent(agent)
                .withMetadata(ManagedSession.AGENT, ManagedSession.OWN));
    }

    @Override
    public ManagedSession useAgent(SessionId sessionId, AgentId agent) {
        AgentDefinition definition = agents.find(agent).map(ManagedAgent::definition).orElseThrow(() ->
                new IllegalArgumentException("There is no agent " + agent.value()));
        // The definition is kept too: what works for the session between turns has a model to ask.
        return changed(sessionId, session -> session.withAgent(definition)
                .withMetadata(ManagedSession.AGENT_ID, agent.value())
                .withMetadata(ManagedSession.AGENT, ManagedSession.REF));
    }

    @Override
    public Optional<AgentDefinition> agentOf(SessionId sessionId) {
        Session session = existing(sessionId);
        if (agentOf(session) instanceof SessionAgent.Ref ref) {
            Optional<AgentDefinition> current = agents.find(ref.id()).map(ManagedAgent::definition);
            if (current.isPresent()) {
                return current;
            }
        }
        return Optional.ofNullable(session.agent());
    }

    private static SessionAgent agentOf(Session session) {
        AgentId id = session.metadata().get(ManagedSession.AGENT_ID) instanceof String stored
                ? AgentId.of(stored) : null;
        if (id != null && ManagedSession.REF.equals(session.metadata().get(ManagedSession.AGENT))) {
            return new SessionAgent.Ref(id);
        }
        return session.agent() == null ? null : new SessionAgent.Own(session.agent(), id);
    }

    @Override
    public void delete(SessionId sessionId) {
        sessions.findByParent(sessionId).forEach(child -> delete(child.id()));
        sessions.find(sessionId).ifPresent(session -> {
            messages.deleteByConversation(RepositorySessionStore.conversationOf(session));
            sessions.delete(sessionId);
            ManagedSession deleted = managed(session);
            cleanups.forEach(cleanup -> cleanup.deleted(deleted));
            // As with opening: a session under another one was in no list of the user's.
            if (session.parentId() == null) {
                lifecycle.tell(session.userId(), new SessionLifecycleEvent.Deleted(sessionId));
            }
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
        return sessions.update(sessionId, change).map(this::managed)
                .orElseThrow(() -> missing(sessionId));
    }

    private Session existing(SessionId sessionId) {
        return sessions.find(sessionId).orElseThrow(() -> missing(sessionId));
    }

    private static IllegalArgumentException missing(SessionId sessionId) {
        return new IllegalArgumentException("There is no session " + sessionId.value());
    }

    private ManagedSession managed(Session session) {
        return new ManagedSession(
                session.id(),
                session.userId(),
                session.parentId(),
                agentOf(session),
                session.metadata().get(ManagedSession.TITLE) instanceof String title ? title : null,
                session.metadata().get(ManagedSession.TYPE) instanceof String type ? type : ManagedSession.CHAT,
                lifecycle.statusOf(session.id()),
                new SessionDirectories(
                        session.metadata().get(ManagedSession.WORKING_DIRECTORY) instanceof String working
                                ? working : null,
                        session.metadata().get(ManagedSession.ADDITIONAL_DIRECTORIES) instanceof List<?> more
                                ? more.stream().map(String::valueOf).toList() : List.of()),
                session.createdAt(),
                session.metadata());
    }

    @Override
    public Subscription lifecycle(UserId user, SessionLifecycleListener listener) {
        return lifecycle.subscribe(user, listener);
    }
}
