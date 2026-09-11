package ai.mindconnect.agent.runtime.service;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.domain.session.SessionAgent;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.tools.todo.TodoListRepository;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.AuthenticationInfo;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.common.DomainException;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.ConversationType;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.Participant;
import ai.mindconnect.message.port.in.ConversationManager;

import ai.mindconnect.agent.runtime.service.approval.ToolApprovalStore;
import ai.mindconnect.agent.runtime.service.stream.UserChannels;
import ai.mindconnect.agent.runtime.service.stream.UserEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * CRUD operations on {@link AgentSession} — opening new chats, looking up,
 * listing, history loading, deleting sessions and their associated state.
 *
 * <p>Stateless and thread-safe. Holds no turn-execution logic; pairs with
 * {@code AgentChatService}, which depends on this service for session
 * lifecycle while owning everything around turn execution and memory.
 */
public class AgentSessionService {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionService.class);

    /** No paging: {@link #loadHistory} returns the conversation entire. */
    private static final int LOAD_ALL = Integer.MAX_VALUE;

    private final AgentDefinitionRepository definitionRepository;
    private final AgentSessionRepository sessionRepository;
    private final ConversationManager conversationManager;
    private final WorkingMemoryRepository workingMemoryRepository;
    private final ConversationSummaryRepository summaryRepository;
    private final TodoListRepository todoListRepository;
    private final ToolApprovalStore approvalStore;
    private final UserChannels userChannels;
    private final WorkingDirPolicy workingDirPolicy;
    /** Where a session's own directory lives — none when the runtime has no users' home. */
    private final UserHome userHome;

    /** Without a working-directory policy: any existing directory may become a session's. */
    public AgentSessionService(AgentDefinitionRepository definitionRepository,
                                AgentSessionRepository sessionRepository,
                                ConversationManager conversationManager,
                                WorkingMemoryRepository workingMemoryRepository,
                                ConversationSummaryRepository summaryRepository,
                                TodoListRepository todoListRepository,
                                ToolApprovalStore approvalStore,
                                UserChannels userChannels) {
        this(definitionRepository, sessionRepository, conversationManager, workingMemoryRepository,
                summaryRepository, todoListRepository, approvalStore, userChannels,
                WorkingDirPolicy.unrestricted());
    }

    public AgentSessionService(AgentDefinitionRepository definitionRepository,
                                AgentSessionRepository sessionRepository,
                                ConversationManager conversationManager,
                                WorkingMemoryRepository workingMemoryRepository,
                                ConversationSummaryRepository summaryRepository,
                                TodoListRepository todoListRepository,
                                ToolApprovalStore approvalStore,
                                UserChannels userChannels,
                                WorkingDirPolicy workingDirPolicy) {
        this(definitionRepository, sessionRepository, conversationManager, workingMemoryRepository,
                summaryRepository, todoListRepository, approvalStore, userChannels,
                workingDirPolicy, UserHome.none());
    }

    /** The full constructor: with the users' home a session's own directory lives in. */
    public AgentSessionService(AgentDefinitionRepository definitionRepository,
                                AgentSessionRepository sessionRepository,
                                ConversationManager conversationManager,
                                WorkingMemoryRepository workingMemoryRepository,
                                ConversationSummaryRepository summaryRepository,
                                TodoListRepository todoListRepository,
                                ai.mindconnect.agent.runtime.service.approval.ToolApprovalStore approvalStore,
                                ai.mindconnect.agent.runtime.service.stream.UserChannels userChannels,
                                WorkingDirPolicy workingDirPolicy,
                                UserHome userHome) {
        this.workingDirPolicy = workingDirPolicy == null ? WorkingDirPolicy.unrestricted() : workingDirPolicy;
        this.userHome = userHome == null ? UserHome.none() : userHome;
        this.definitionRepository = definitionRepository;
        this.sessionRepository = sessionRepository;
        this.conversationManager = conversationManager;
        this.workingMemoryRepository = workingMemoryRepository;
        this.summaryRepository = summaryRepository;
        this.todoListRepository = todoListRepository;
        this.approvalStore = approvalStore;
        this.userChannels = userChannels;
    }

    /**
     * Opens a brand-new top-level chat session for the given agent: creates
     * a conversation with USER/AGENT participants and persists the session.
     * No directory is named, so it works in its own — the same as every chat
     * that does not choose one, however it is opened.
     */
    public AgentSession openChat(AgentId agentDefinitionId, UserId userId) {
        return openChat(agentDefinitionId, userId, (String) null);
    }

    /**
     * Opens a top-level chat session that works in {@code workingDir} — the
     * directory the user launched the CLI in, the project the chat is
     * about. Validated by the {@link WorkingDirPolicy}: it has to exist and,
     * where a root is configured, lie under it. {@code null} for none.
     */
    public AgentSession openChat(AgentId agentDefinitionId, UserId userId, String workingDir) {
        return openChat(agentDefinitionId, userId, workingDir, List.of());
    }

    /**
     * Same, with additional directories the session may reach by absolute
     * path beside its working directory. Each is validated like the
     * working directory.
     */
    public AgentSession openChat(AgentId agentDefinitionId, UserId userId,
                                 String workingDir, List<String> additionalDirs) {
        WorkingDirPolicy policy = workingDirPolicy.forUser(userId);
        String dir = policy.validate(workingDir);
        List<String> extras = validateAll(policy, additionalDirs);
        return inOwnDirectoryWhenNone(openChat(agentDefinitionId, userId, null, null, null, dir, extras));
    }

    /**
     * A session opened without a working directory works in its own: a
     * directory under the user's home, created here, where its uploads land
     * and its scratch files go. Nothing changes for a session that chose a
     * directory, or when the runtime has no users' home.
     */
    private AgentSession inOwnDirectoryWhenNone(AgentSession session) {
        if (session.hasWorkingDir()) return session;
        return userHome.sessionDirOf(session.userId(), session.id())
                .map(own -> sessionRepository.save(session.withWorkingDir(own.toString())))
                .orElse(session);
    }

    /** A session's own directory — where its uploads are — when the runtime has a users' home. */
    public java.util.Optional<java.nio.file.Path> sessionDir(SessionId sessionId) {
        AgentSession session = findSession(sessionId);
        return userHome.sessionDirOf(session.userId(), session.id());
    }

    /** The users' home this runtime keeps session directories under; may be unconfigured. */
    public UserHome userHome() {
        return userHome;
    }

    /**
     * A session that moves away from its own directory keeps it reachable:
     * the uploads and scratch files in there must not vanish from the tools
     * because the user opened a project. Only a directory that exists is
     * kept — a session that never had one gets nothing added.
     */
    private List<String> keepingOwnDirectory(AgentSession session, String newDir, List<String> extras) {
        return userHome.existingSessionDirOf(session.userId(), session.id())
                .map(java.nio.file.Path::toString)
                .filter(own -> !own.equals(newDir) && !extras.contains(own))
                .map(own -> {
                    List<String> merged = new java.util.ArrayList<>(extras);
                    merged.add(own);
                    return List.copyOf(merged);
                })
                .orElse(extras);
    }

    /**
     * Moves a session to another working directory — {@code /cd} in the CLI,
     * the settings dialog in the chat. Validated like at open; {@code null}
     * or blank clears it, so the tools fall back to the configured default.
     * Sub-agents already running keep the directory they were spawned with.
     */
    public AgentSession changeWorkingDir(SessionId sessionId, String workingDir) {
        return changeWorkingDir(sessionId, workingDir, null);
    }

    /**
     * Moves a session to another working directory and replaces its
     * additional directories in one go — the chat's dialog, the REST
     * endpoint. {@code null} keeps the additional directories as they are.
     *
     * <p>Only a directory the session does not have yet is checked against
     * the root; see {@link #directoriesHeld}. Removing one directory resends
     * the rest, and the rest must not fail on a check they already passed —
     * or, for the session's own directory, never had to.
     */
    public AgentSession changeWorkingDir(SessionId sessionId, String workingDir, List<String> additionalDirs) {
        // Clearing counts as a choice too: it would take the chat out of its own directory.
        workingDirPolicy.requireChoice();
        AgentSession session = findSession(sessionId);
        WorkingDirPolicy policy = workingDirPolicy.forUser(session.userId());
        java.util.Set<String> held = directoriesHeld(session);
        String dir = policy.validate(workingDir, held);
        List<String> extras = additionalDirs == null ? session.additionalDirs()
                : validateAll(policy, additionalDirs, held);
        return sessionRepository.save(session.withWorkingDir(dir)
                .withAdditionalDirs(keepingOwnDirectory(session, dir, extras)));
    }

    /** Adds one directory to a session's additional directories ({@code /add-dir}). */
    public AgentSession addDirectory(SessionId sessionId, String dir) {
        workingDirPolicy.requireChoice();
        AgentSession session = findSession(sessionId);
        String validated = workingDirPolicy.forUser(session.userId()).validate(dir, directoriesHeld(session));
        if (validated == null) throw new IllegalArgumentException("A directory is required");
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(session.additionalDirs());
        merged.add(validated);
        return sessionRepository.save(session.withAdditionalDirs(List.copyOf(merged)));
    }

    /**
     * The directories a session already has, as recorded: its working
     * directory, its additional ones and its own under the users' home.
     * They passed the policy when they were chosen, or were given by the
     * runtime, which does not ask it — the users' home need not lie under
     * the root at all.
     */
    private java.util.Set<String> directoriesHeld(AgentSession session) {
        java.util.Set<String> held = new java.util.HashSet<>(session.additionalDirs());
        if (session.hasWorkingDir()) held.add(session.workingDir());
        userHome.existingSessionDirOf(session.userId(), session.id())
                .ifPresent(own -> held.add(own.toString()));
        return held;
    }

    /** Every directory validated, blanks dropped, duplicates folded, order kept. */
    private static List<String> validateAll(WorkingDirPolicy policy, List<String> dirs) {
        return validateAll(policy, dirs, java.util.Set.of());
    }

    /** Same, with the directories the session already has passing without the root check. */
    private static List<String> validateAll(WorkingDirPolicy policy, List<String> dirs,
                                            java.util.Set<String> held) {
        if (dirs == null || dirs.isEmpty()) return List.of();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String d : dirs) {
            String validated = policy.validate(d, held);
            if (validated != null) out.add(validated);
        }
        return List.copyOf(out);
    }

    /** The policy sessions' working directories are checked against (per-user roots still need {@code forUser}). */
    /** May users choose a chat's directories here, or does every chat work in its own? */
    public boolean workingDirChoice() {
        return workingDirPolicy.allowsChoice();
    }

    public WorkingDirPolicy workingDirPolicy() {
        return workingDirPolicy;
    }

    /**
     * Opens a chat session, optionally tagged as a sub-agent of another
     * session/turn. {@code parentSessionId} and {@code parentTurnId} are
     * persisted onto the {@link AgentSession} so the trace UI can walk
     * the call tree without scanning every conversation directory.
     * {@code parentToolCallId} ties the spawn back to the specific
     * TOOL_CALL message in the parent's history that triggered it —
     * needed to nest UI cards correctly when a parent turn spawns
     * several sub-agents in parallel.
     * <p>
     * Pass {@code null} for all three parents on top-level (user-initiated) sessions.
     */
    public AgentSession openChat(AgentId agentDefinitionId, UserId userId,
                                  SessionId parentSessionId, ChatTurnId parentTurnId,
                                  String parentToolCallId) {
        return openChat(agentDefinitionId, userId, parentSessionId, parentTurnId, parentToolCallId,
                null, List.of());
    }

    /**
     * Same, with the directories the session starts in — for a sub-agent the
     * parent's, so it works where the user works. Already validated: a
     * parent's directories were checked when the parent got them.
     */
    public AgentSession openChat(AgentId agentDefinitionId, UserId userId,
                                  SessionId parentSessionId, ChatTurnId parentTurnId,
                                  String parentToolCallId, String workingDir,
                                  List<String> additionalDirs) {
        AgentDefinition def = definitionRepository.findById(agentDefinitionId)
                .orElseThrow(() -> DomainException.notFound("AgentDefinition", agentDefinitionId.toString()));

        ConversationId conversationId = ConversationId.random();
        List<Participant> participants = List.of(
                Participant.user(conversationId, userId, userId.value()),
                Participant.agent(conversationId, agentDefinitionId, def.name())
        );
        conversationManager.createConversation(conversationId, "Chat with " + def.name(),
                ConversationType.USER_AGENT, participants);

        AgentSession session = AgentSession.startSubAgent(agentDefinitionId, userId,
                conversationId, parentSessionId, parentTurnId, parentToolCallId)
                .withWorkingDir(workingDir)
                .withAdditionalDirs(additionalDirs);
        AgentSession saved = sessionRepository.create(session);
        // A sub-agent's session is the parent turn's business, not news for
        // the user's session list.
        if (parentSessionId == null) {
            userChannels.publish(userId, new UserEvent
                    .SessionStarted(saved.id(), agentDefinitionId));
        }
        return saved;
    }

    /**
     * Opens a sub-session for an agent the registry has never heard of — a
     * project's own, defined in a file beside its code. Same as the
     * sub-agent {@code openChat} above, except the definition travels with
     * the session instead of being looked up by id, because there is
     * nothing to look up.
     */
    public AgentSession openSubChat(ai.mindconnect.agent.runtime.domain.session.SessionAgent agent,
                                    UserId userId, SessionId parentSessionId, ChatTurnId parentTurnId,
                                    String parentToolCallId, String workingDir,
                                    List<String> additionalDirs) {
        ConversationId conversationId = ConversationId.random();
        List<Participant> participants = List.of(
                Participant.user(conversationId, userId, userId.value()),
                Participant.agent(conversationId, agent.id(), agent.label())
        );
        conversationManager.createConversation(conversationId, "Chat with " + agent.label(),
                ConversationType.USER_AGENT, participants);

        AgentSession session = AgentSession.startSubAgent(agent.id(), userId,
                conversationId, parentSessionId, parentTurnId, parentToolCallId)
                .withWorkingDir(workingDir)
                .withAdditionalDirs(additionalDirs)
                .withSessionAgents(List.of(agent));
        return sessionRepository.save(session);
    }

    /**
     * Opens a chat for a session agent — either an inline one the user
     * assembled from a model and some tools, or a reference to a registry
     * agent with this chat's overrides.
     *
     * <p>{@code agentDefinitionId} is set to the session agent's id either
     * way, so the message attribution and the
     * conversation participant keep working unchanged. For an inline agent
     * that id resolves to nothing in the registry — which is the point: the
     * chat is not findable under any agent, because it belongs to none.
     */
    public AgentSession openChat(SessionAgent agent, UserId userId) {
        ConversationId conversationId = ConversationId.random();
        List<Participant> participants = List.of(
                Participant.user(conversationId, userId, userId.value()),
                Participant.agent(conversationId, agent.id(), agent.label())
        );
        conversationManager.createConversation(conversationId, "Chat with " + agent.label(),
                ConversationType.USER_AGENT, participants);

        AgentSession session = AgentSession
                .start(agent.id(), userId, conversationId)
                .withSessionAgents(List.of(agent));
        AgentSession saved = inOwnDirectoryWhenNone(sessionRepository.create(session));
        userChannels.publish(userId, new UserEvent
                .SessionStarted(saved.id(), agent.id()));
        return saved;
    }

    /**
     * Swaps the agent a session runs — the model-and-tools dialog, or
     * attaching the chat to a registry agent.
     *
     * <p>{@code agentDefinitionId} moves with the agent. It is the id the
     * session is listed under, and both
     * should describe the agent the chat actually runs — a chat detached from
     * "Poet" has no business still appearing under {@code ?agentId=Poet}.
     * The messages written so far keep the old id, which is right: they were
     * said by that agent. What the new agent does not inherit is the previous
     * one's memory — a different agent, a different memory.
     */
    public AgentSession replaceSessionAgent(SessionId sessionId,
                                            SessionAgent agent) {
        return change(sessionId, session -> session.withSessionAgent(agent));
    }

    public List<AgentSession> listSessions(AgentId agentDefinitionId, UserId userId) {
        return sessionRepository.findByAgent(agentDefinitionId, userId);
    }

    public AgentSession findSession(SessionId sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> DomainException.notFound("AgentSession", sessionId.toString()));
    }

    /**
     * The session's whole conversation, oldest first. Not a page: this is
     * what the chat shows, and a cap here cuts away the newest messages,
     * not the oldest. A conversation past the cap kept displaying its
     * opening and silently dropped everything after it, the message just
     * sent included. The runtime has always read the conversation entire;
     * the display now agrees with it.
     */
    public List<Message> loadHistory(SessionId sessionId) {
        AgentSession session = findSession(sessionId);
        return conversationManager.loadHistory(session.conversationId(), new PageRequest(0, LOAD_ALL));
    }

    /**
     * Permanently deletes a session and all its associated data (working
     * memory snapshot, conversation summaries). Conversation messages
     * themselves belong to the conversation, not the session, and are kept.
     */
    public void deleteSession(SessionId sessionId) {
        AgentSession session = findSession(sessionId);
        AuthenticationInfo auth = AuthenticationInfo.of(session.userId());
        workingMemoryRepository.delete(sessionId, auth);
        summaryRepository.deleteByConversation(session.conversationId());
        todoListRepository.deleteBySession(sessionId);
        approvalStore.deleteForSession(sessionId);
        sessionRepository.deleteById(sessionId);
        deleteOwnDirectory(session);
        log.info("Deleted session {} and associated data", sessionId);
    }

    /**
     * The chat's own directory under the user's home goes with it — its
     * uploads, notes and logs are nobody else's, and on a server they would
     * otherwise pile up. Only that directory: one the user chose is theirs
     * and is never touched. Links inside are removed, not followed.
     */
    private void deleteOwnDirectory(AgentSession session) {
        userHome.existingSessionDirOf(session.userId(), session.id()).ifPresent(dir -> {
            try (var paths = java.nio.file.Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        java.nio.file.Files.deleteIfExists(path);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
            } catch (java.io.IOException | java.io.UncheckedIOException e) {
                log.warn("The directory of deleted session {} could not be removed entirely: {}",
                        session.id(), e.getMessage());
            }
        });
    }

    public int deleteMessages(SessionId sessionId, int fromSeq, int toSeq) {
        AgentSession session = findSession(sessionId);
        if (fromSeq > toSeq) throw new IllegalArgumentException("fromSeq must be <= toSeq");
        int count = conversationManager.deleteMessages(session.conversationId(), fromSeq, toSeq);
        if (count > 0) log.info("Deleted {} message(s) (seq {}-{}) from conversation {}",
                count, fromSeq, toSeq, session.conversationId());
        return count;
    }

    /** Sets the title of the given session — the user renaming the chat. */
    public AgentSession updateTitle(SessionId sessionId, String title) {
        return change(sessionId, session -> session.withTitle(title));
    }

    /**
     * Sets {@code title} unless the session has one by now — the title generator
     * after the first exchange, which must not overwrite a name the user gave the
     * chat while the title was being generated.
     *
     * @return the session as stored, with whichever title it has
     */
    public AgentSession titleIfUntitled(SessionId sessionId, String title) {
        return change(sessionId, session -> session.title() == null ? session.withTitle(title) : session);
    }

    /**
     * "Allow for this session": from now on, calls of {@code toolName} in this
     * session run without asking. Per tool name, never per parameter set —
     * see {@link AgentSession#approvedTools()}.
     */
    public AgentSession approveToolForSession(SessionId sessionId, String toolName) {
        return change(sessionId, session -> session.approvedTools().contains(toolName)
                ? session
                : session.withApprovedTool(toolName));
    }

    /**
     * Applies {@code change} to the stored session in one step, so a change made
     * meanwhile by another thread — a tool activation, an attached file — is kept.
     */
    private AgentSession change(SessionId sessionId, UnaryOperator<AgentSession> change) {
        return sessionRepository.update(sessionId, change)
                .orElseThrow(() -> DomainException.notFound("AgentSession", sessionId.toString()));
    }

    /** The sub-sessions spawned from {@code parentSessionId} by run_agent calls. */
    public java.util.List<AgentSession> subSessions(SessionId parentSessionId) {
        return sessionRepository.findByParentSession(parentSessionId);
    }

    /**
     * Whether {@code toolName} is approved anywhere on this session's parent
     * chain. Approvals INHERIT DOWNWARD: "allow for this session" given at
     * the root covers every sub-agent of that conversation — a sub-session
     * lives exactly one run_agent call, so storing on it would be pointless.
     */
    public boolean isToolApproved(SessionId sessionId, String toolName) {
        AgentSession session = findSession(sessionId);
        while (session != null) {
            if (session.approvedTools().contains(toolName)) return true;
            session = session.parentSessionId() == null ? null
                    : sessionRepository.findById(session.parentSessionId()).orElse(null);
        }
        return false;
    }

    /** The topmost session of this session's parent chain (itself when root). */
    public AgentSession rootSession(SessionId sessionId) {
        AgentSession session = findSession(sessionId);
        while (session.parentSessionId() != null) {
            AgentSession parent = sessionRepository.findById(session.parentSessionId()).orElse(null);
            if (parent == null) break;
            session = parent;
        }
        return session;
    }

    /** How many parent hops above this session — 0 for a root session. */
    public int sessionDepth(SessionId sessionId) {
        int depth = 0;
        AgentSession session = findSession(sessionId);
        while (session.parentSessionId() != null) {
            AgentSession parent = sessionRepository.findById(session.parentSessionId()).orElse(null);
            if (parent == null) break;
            session = parent;
            depth++;
        }
        return depth;
    }
}
