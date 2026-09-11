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

    public AgentSessionService(AgentDefinitionRepository definitionRepository,
                                AgentSessionRepository sessionRepository,
                                ConversationManager conversationManager,
                                WorkingMemoryRepository workingMemoryRepository,
                                ConversationSummaryRepository summaryRepository,
                                TodoListRepository todoListRepository,
                                ToolApprovalStore approvalStore,
                                UserChannels userChannels) {
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
     */
    public AgentSession openChat(AgentId agentDefinitionId, UserId userId) {
        return openChat(agentDefinitionId, userId, null, null, null);
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
                conversationId, parentSessionId, parentTurnId, parentToolCallId);
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
     * Opens a chat for a session agent — either an inline one the user
     * assembled from a model and some tools, or a reference to a registry
     * agent with this chat's overrides.
     *
     * <p>{@code agentDefinitionId} is set to the session agent's id either
     * way, so the workspace scope, the message attribution and the
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
        AgentSession saved = sessionRepository.create(session);
        userChannels.publish(userId, new UserEvent
                .SessionStarted(saved.id(), agent.id()));
        return saved;
    }

    /**
     * Swaps the agent a session runs — the model-and-tools dialog, or
     * attaching the chat to a registry agent.
     *
     * <p>{@code agentDefinitionId} moves with the agent. It is the id the
     * session is listed under and the key its workspace is filed by, and both
     * should describe the agent the chat actually runs — a chat detached from
     * "Poet" has no business still appearing under {@code ?agentId=Poet}.
     * The messages written so far keep the old id, which is right: they were
     * said by that agent. What the new agent does not inherit is the previous
     * one's workspace — a different agent, a different memory.
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
        log.info("Deleted session {} and associated data", sessionId);
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
