package ai.mindconnect.agent.starter.runtime.bridge;

import ai.mindconnect.agent.runtime.domain.AttachedFile;
import ai.mindconnect.agent.runtime.capabilities.attachment.AttachmentPlugin;
import java.util.function.Function;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.session.SessionAgent;
import ai.mindconnect.agent.runtime.domain.session.SessionAgentRef;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.capabilities.permission.SessionApprovalMemory;
import ai.mindconnect.agent.runtime.capabilities.toolsearch.SessionToolActivation;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.SessionStatus;
import ai.mindconnect.agent.runtime.engine.adapter.store.RepositorySessionStore;
import ai.mindconnect.agent.runtime.engine.port.out.SessionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;

/**
 * The earlier runtime's session repository, kept in the session repository
 * of the new one: a chat's session is one record, read by the chat page the
 * earlier way and by the runtime on the plugin kernel its own way.
 *
 * <p>A session written the earlier way is kept whole in the session's
 * metadata, and what both sides know is spelled out beside it under the
 * keys the management and the capabilities read: title, type, status,
 * directories, the tools approved and found, the files attached. Read back the earlier way it
 * is the whole one with those entries laid over it — the runtime may have
 * given the chat a title, or remembered an approval, since.
 *
 * <p>The agent a chat chose is spelled out as well, the way the management
 * keeps it: a chat that names a stored agent and changes nothing about it
 * refers to that agent; one that chose a model, tools or a prompt of its
 * own runs on a definition of its own, put together when it is written.
 *
 * <p>Sessions of the earlier store are taken over as they are asked for:
 * one by its id, a user's when their list is read, the ones under a session
 * with it.
 */
final class ManagedAgentSessionRepository implements AgentSessionRepository {

    /** Metadata of a session: the session as the earlier runtime wrote it, whole. */
    static final String EARLIER = "earlier.session";

    private final Supplier<SessionRepository> sessions;
    private final AgentSessionRepository earlier;
    private final ObjectMapper json;
    private final Supplier<NamespaceProvider> namespaces;
    /** The definition a session written the earlier way amounts to; empty when its agent is gone. */
    private final Function<AgentSession, Optional<AgentDefinition>> agentOf;
    /** The users whose sessions were taken over, per namespace. */
    private final Set<String> takenOver = ConcurrentHashMap.newKeySet();

    ManagedAgentSessionRepository(Supplier<SessionRepository> sessions, AgentSessionRepository earlier,
                                  ObjectMapper json, Supplier<NamespaceProvider> namespaces,
                                  Function<AgentSession, Optional<AgentDefinition>> agentOf) {
        this.agentOf = agentOf;
        this.sessions = sessions;
        this.earlier = earlier;
        this.json = json;
        this.namespaces = namespaces;
    }

    @Override
    public AgentSession create(AgentSession session) {
        sessions.get().create(session(session, null));
        return session;
    }

    @Override
    public Optional<AgentSession> update(SessionId id, UnaryOperator<AgentSession> change) {
        takeOver(id);
        return sessions.get().update(runtimeId(id), stored -> session(change.apply(earlier(stored)), stored))
                .map(this::earlier);
    }

    @Override
    public Optional<AgentSession> findById(SessionId id) {
        takeOver(id);
        return sessions.get().find(runtimeId(id)).map(this::earlier);
    }

    @Override
    public List<AgentSession> findByAgent(AgentId agent, UserId user) {
        return findByUser(user).stream().filter(session -> agent.equals(session.agentDefinitionId())).toList();
    }

    @Override
    public List<AgentSession> findByUser(UserId user) {
        if (takenOver.add(namespaces.get().current() + "/" + user.value())) {
            earlier.findByUser(user).forEach(this::takeOver);
        }
        return sessions.get().findByUser(ai.mindconnect.agent.runtime.api.domain.UserId.of(user.value())).stream()
                .map(this::earlier).toList();
    }

    @Override
    public List<AgentSession> findByParentSession(SessionId parent) {
        earlier.findByParentSession(parent).forEach(this::takeOver);
        return sessions.get().findByParent(runtimeId(parent)).stream().map(this::earlier).toList();
    }

    /** From both stores: a session left in the earlier one would be taken over again. */
    @Override
    public void deleteById(SessionId id) {
        sessions.get().delete(runtimeId(id));
        earlier.deleteById(id);
    }

    private void takeOver(SessionId id) {
        Optional<Session> stored = sessions.get().find(runtimeId(id));
        if (stored.isEmpty() || !stored.get().metadata().containsKey(EARLIER)) {
            earlier.findById(id).ifPresent(this::takeOver);
        } else if (!stored.get().metadata().containsKey(ManagedSession.AGENT)) {
            // Kept here before the chat's agent was spelled out: written once more, as it is.
            sessions.get().update(runtimeId(id), known -> session(earlier(known), known));
        }
    }

    /**
     * Into the runtime's store, unless it is there as the chat wrote it. A
     * session the runtime already knows without that — it ran a turn in it
     * before the chat's side was kept here — is completed, not replaced:
     * what the runtime keeps in it stays.
     */
    private void takeOver(AgentSession kept) {
        Optional<Session> stored = sessions.get().find(runtimeId(kept.id()));
        if (stored.isEmpty()) {
            sessions.get().create(session(kept, null));
        } else if (!stored.get().metadata().containsKey(EARLIER)) {
            sessions.get().update(runtimeId(kept.id()), known -> {
                Session completed = session(kept, known);
                for (Map.Entry<String, Object> entry : known.metadata().entrySet()) {
                    completed = completed.withMetadata(entry.getKey(), entry.getValue());
                }
                return completed;
            });
        }
    }

    private static ai.mindconnect.agent.runtime.api.domain.SessionId runtimeId(SessionId id) {
        return ai.mindconnect.agent.runtime.api.domain.SessionId.of(id.value());
    }

    /**
     * The earlier session as the runtime's.
     *
     * @param stored the session as it stands, whose agent and whose other
     *               metadata — what the runtime keeps there — stay;
     *               {@code null} for a new one
     */
    @SuppressWarnings("unchecked")
    private Session session(AgentSession written, Session stored) {
        Map<String, Object> metadata = new HashMap<>(stored == null ? Map.of() : stored.metadata());
        metadata.put(EARLIER, json.convertValue(written, Map.class));
        metadata.put(RepositorySessionStore.CONVERSATION_ID, written.conversationId().value());
        // The agent is spelled out when the writer chose one; any other write leaves it as it is kept —
        // the management may have given the session another since, which the earlier session does not show.
        AgentDefinition agent = stored == null ? null : stored.agent();
        if (stored == null || !metadata.containsKey(ManagedSession.AGENT) || !sameAgent(written, earlier(stored))) {
            SessionAgent chosen = written.mainAgent().orElse(null);
            boolean refers = chosen == null || chosen instanceof SessionAgentRef ref && unchanged(ref);
            metadata.put(ManagedSession.AGENT_ID,
                    (chosen instanceof SessionAgentRef ref ? ref.id() : written.agentDefinitionId()).value());
            metadata.put(ManagedSession.AGENT, refers ? ManagedSession.REF : ManagedSession.OWN);
            // Kept for a chat that refers to its agent as well: what the session last ran on.
            agent = agentOf.apply(written).orElse(agent);
        }
        metadata.put(ManagedSession.TYPE, written.type());
        metadata.put(ManagedSession.STATUS, written.status().name());
        metadata.put(ManagedSession.ADDITIONAL_DIRECTORIES, written.additionalDirs());
        metadata.put(SessionApprovalMemory.APPROVED_TOOLS, List.copyOf(written.approvedTools()));
        metadata.put(SessionToolActivation.ACTIVATED_TOOLS, written.activatedTools());
        metadata.put(AttachmentPlugin.ATTACHMENTS, json.convertValue(written.attachedFiles(), List.class));
        // An entry cannot hold nothing: a session without a title or a directory has no such entry.
        if (written.title() == null) {
            metadata.remove(ManagedSession.TITLE);
        } else {
            metadata.put(ManagedSession.TITLE, written.title());
        }
        if (written.workingDir() == null) {
            metadata.remove(ManagedSession.WORKING_DIRECTORY);
        } else {
            metadata.put(ManagedSession.WORKING_DIRECTORY, written.workingDir());
        }
        return new Session(runtimeId(written.id()),
                ai.mindconnect.agent.runtime.api.domain.UserId.of(written.userId().value()),
                written.parentSessionId() == null ? null : runtimeId(written.parentSessionId()),
                agent,
                metadata,
                stored != null ? stored.createdAt()
                        : written.startedAt() == null ? Instant.now() : written.startedAt());
    }

    /** The runtime's session as the earlier one: what was written, with the entries both sides share laid over it. */
    private AgentSession earlier(Session session) {
        Map<String, Object> metadata = session.metadata();
        AgentSession written = metadata.get(EARLIER) instanceof Map<?, ?> whole
                ? json.convertValue(whole, AgentSession.class) : openedByTheRuntime(session);
        return new AgentSession(written.id(), written.agentDefinitionId(), written.userId(),
                written.conversationId(),
                metadata.get(ManagedSession.TITLE) instanceof String title ? title : null,
                metadata.get(ManagedSession.STATUS) instanceof String status
                        ? SessionStatus.valueOf(status) : written.status(),
                written.startedAt(), written.completedAt(), written.parentSessionId(), written.parentTurnId(),
                written.parentToolCallId(),
                names(metadata.get(SessionToolActivation.ACTIVATED_TOOLS)),
                // Kept by the management since: what is attached now, not what was when this was written.
                metadata.get(AttachmentPlugin.ATTACHMENTS) instanceof List<?> attached
                        ? json.convertValue(attached, json.getTypeFactory()
                                .constructCollectionType(List.class, AttachedFile.class))
                        : written.attachedFiles(),
                Set.copyOf(names(metadata.get(SessionApprovalMemory.APPROVED_TOOLS))),
                written.sessionAgents(),
                metadata.get(ManagedSession.WORKING_DIRECTORY) instanceof String directory ? directory : null,
                names(metadata.get(ManagedSession.ADDITIONAL_DIRECTORIES)),
                written.type());
    }

    /**
     * A session nobody wrote the earlier way — the runtime opened it itself,
     * for a sub-agent — from what the runtime knows of it. Its agent is
     * named by the id the application gave that agent, where it has one.
     */
    private static AgentSession openedByTheRuntime(Session session) {
        Object agentId = session.agent() == null ? null
                : session.agent().metadata().get(AgentDefinitionMapper.AGENT_ID);
        return new AgentSession(SessionId.of(session.id().value()),
                agentId instanceof String id ? AgentId.of(id) : AgentId.of(session.id().value()),
                UserId.of(session.userId().value()),
                ai.mindconnect.message.domain.ConversationId.of(
                        RepositorySessionStore.conversationOf(session).value()),
                null, SessionStatus.ACTIVE, session.createdAt(), null,
                session.parentId() == null ? null : SessionId.of(session.parentId().value()), null, null);
    }

    private static boolean sameAgent(AgentSession one, AgentSession other) {
        return one.agentDefinitionId().equals(other.agentDefinitionId())
                && one.sessionAgents().equals(other.sessionAgents());
    }

    /** Does the chat take the stored agent as it is? */
    private static boolean unchanged(SessionAgentRef ref) {
        return (ref.llmConfigName() == null || ref.llmConfigName().isBlank()) && !ref.hasPromptOverride()
                && ref.tools() == null && ref.callableAgents() == null;
    }

    private static List<String> names(Object stored) {
        return stored instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }
}
