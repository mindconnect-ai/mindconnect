package ai.mindconnect.agent.service;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.agent.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.tools.todo.TodoListRepository;
import ai.mindconnect.agent.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.common.AuthenticationInfo;
import ai.mindconnect.common.DomainException;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.ConversationType;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.Participant;
import ai.mindconnect.message.port.in.ConversationManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

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

    private final AgentDefinitionRepository definitionRepository;
    private final AgentSessionRepository sessionRepository;
    private final ConversationManager conversationManager;
    private final WorkingMemoryRepository workingMemoryRepository;
    private final ConversationSummaryRepository summaryRepository;
    private final TodoListRepository todoListRepository;
    private final ai.mindconnect.agent.service.approval.ToolApprovalStore approvalStore;

    public AgentSessionService(AgentDefinitionRepository definitionRepository,
                                AgentSessionRepository sessionRepository,
                                ConversationManager conversationManager,
                                WorkingMemoryRepository workingMemoryRepository,
                                ConversationSummaryRepository summaryRepository,
                                TodoListRepository todoListRepository,
                                ai.mindconnect.agent.service.approval.ToolApprovalStore approvalStore) {
        this.definitionRepository = definitionRepository;
        this.sessionRepository = sessionRepository;
        this.conversationManager = conversationManager;
        this.workingMemoryRepository = workingMemoryRepository;
        this.summaryRepository = summaryRepository;
        this.todoListRepository = todoListRepository;
        this.approvalStore = approvalStore;
    }

    /**
     * Opens a brand-new top-level chat session for the given agent: creates
     * a conversation with USER/AGENT participants and persists the session.
     */
    public AgentSession openChat(UUID agentDefinitionId, Namespace namespace, String userId) {
        return openChat(agentDefinitionId, namespace, userId, null, null, null);
    }

    /**
     * Back-compat overload (no parent tool-call id). Kept so existing
     * call-sites that don't yet thread the {@code tool_call_id} through
     * keep compiling. Prefer the four-arg variant below for sub-agent
     * spawns so the UI can nest the spawned session's activity under
     * the exact {@code run_agent} card that started it.
     */
    public AgentSession openChat(UUID agentDefinitionId, Namespace namespace, String userId,
                                  UUID parentSessionId, UUID parentTurnId) {
        return openChat(agentDefinitionId, namespace, userId, parentSessionId, parentTurnId, null);
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
    public AgentSession openChat(UUID agentDefinitionId, Namespace namespace, String userId,
                                  UUID parentSessionId, UUID parentTurnId,
                                  String parentToolCallId) {
        AgentDefinition def = definitionRepository.findById(agentDefinitionId)
                .orElseThrow(() -> DomainException.notFound("AgentDefinition", agentDefinitionId.toString()));

        List<Participant> participants = List.of(
                Participant.user(UUID.randomUUID(), userId, userId),
                Participant.agent(UUID.randomUUID(), agentDefinitionId.toString(), def.name())
        );
        var conversation = conversationManager.createConversation(
                namespace, "Chat with " + def.name(), ConversationType.USER_AGENT, participants);

        AgentSession session = AgentSession.startSubAgent(agentDefinitionId, namespace, userId,
                conversation.id(), parentSessionId, parentTurnId, parentToolCallId);
        return sessionRepository.save(session);
    }

    public List<AgentSession> listSessions(UUID agentDefinitionId, Namespace namespace, String userId) {
        return sessionRepository.findByAgentDefinitionId(agentDefinitionId, namespace, userId);
    }

    public AgentSession findSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> DomainException.notFound("AgentSession", sessionId.toString()));
    }

    public List<Message> loadHistory(UUID sessionId) {
        AgentSession session = findSession(sessionId);
        return conversationManager.loadHistory(session.conversationId(), new PageRequest(0, 200));
    }

    /**
     * Permanently deletes a session and all its associated data (working
     * memory snapshot, conversation summaries). Conversation messages
     * themselves belong to the conversation, not the session, and are kept.
     */
    public void deleteSession(UUID sessionId) {
        AgentSession session = findSession(sessionId);
        AuthenticationInfo auth = AuthenticationInfo.of(session.userId(), session.namespace());
        workingMemoryRepository.delete(sessionId, auth);
        summaryRepository.deleteByConversationId(session.conversationId());
        todoListRepository.deleteBySession(sessionId);
        approvalStore.deleteForSession(sessionId);
        sessionRepository.deleteById(sessionId);
        log.info("Deleted session {} and associated data", sessionId);
    }

    public int deleteMessages(UUID sessionId, int fromSeq, int toSeq) {
        AgentSession session = findSession(sessionId);
        if (fromSeq > toSeq) throw new IllegalArgumentException("fromSeq must be <= toSeq");
        int count = conversationManager.deleteMessages(session.conversationId(), fromSeq, toSeq);
        if (count > 0) log.info("Deleted {} message(s) (seq {}-{}) from conversation {}",
                count, fromSeq, toSeq, session.conversationId());
        return count;
    }

    /**
     * Sets the title of the given session. Used by the title-generation flow
     * after the first user/agent exchange completes.
     */
    public AgentSession updateTitle(UUID sessionId, String title) {
        AgentSession session = findSession(sessionId);
        return sessionRepository.save(session.withTitle(title));
    }

    /**
     * "Allow for this session": from now on, calls of {@code toolName} in this
     * session run without asking. Per tool name, never per parameter set —
     * see {@link AgentSession#approvedTools()}.
     */
    public AgentSession approveToolForSession(UUID sessionId, String toolName) {
        AgentSession session = findSession(sessionId);
        return sessionRepository.save(session.withApprovedTool(toolName));
    }

    /** The sub-sessions spawned from {@code parentSessionId} by run_agent calls. */
    public java.util.List<AgentSession> subSessions(UUID parentSessionId) {
        return sessionRepository.findByParentSessionId(parentSessionId);
    }

    /**
     * Whether {@code toolName} is approved anywhere on this session's parent
     * chain. Approvals INHERIT DOWNWARD: "allow for this session" given at
     * the root covers every sub-agent of that conversation — a sub-session
     * lives exactly one run_agent call, so storing on it would be pointless.
     */
    public boolean isToolApproved(UUID sessionId, String toolName) {
        AgentSession session = findSession(sessionId);
        while (session != null) {
            if (session.approvedTools().contains(toolName)) return true;
            session = session.parentSessionId() == null ? null
                    : sessionRepository.findById(session.parentSessionId()).orElse(null);
        }
        return false;
    }

    /** The topmost session of this session's parent chain (itself when root). */
    public AgentSession rootSession(UUID sessionId) {
        AgentSession session = findSession(sessionId);
        while (session.parentSessionId() != null) {
            AgentSession parent = sessionRepository.findById(session.parentSessionId()).orElse(null);
            if (parent == null) break;
            session = parent;
        }
        return session;
    }

    /** How many parent hops above this session — 0 for a root session. */
    public int sessionDepth(UUID sessionId) {
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
