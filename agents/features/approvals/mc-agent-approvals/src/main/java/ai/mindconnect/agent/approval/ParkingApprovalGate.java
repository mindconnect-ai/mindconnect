package ai.mindconnect.agent.approval;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.StreamEvent;
import ai.mindconnect.agent.runtime.domain.ToolApproval;
import ai.mindconnect.agent.runtime.port.out.ToolApprovalRepository;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.runtime.service.approval.ApprovalGate;
import ai.mindconnect.agent.runtime.service.approval.ApprovalScope;
import ai.mindconnect.agent.runtime.service.stream.SessionChannels;
import ai.mindconnect.agent.runtime.service.stream.UserChannels;
import ai.mindconnect.agent.runtime.service.stream.UserEvent;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.message.port.in.ConversationManager;
import ai.mindconnect.taskqueue.TaskContext;
import ai.mindconnect.taskqueue.TaskQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The approval gate that parks a flagged call's task until a human answers.
 * One {@link ToolApprovalRepository} entry per open question is the single
 * truth for its card; the answer travels to the parked task as a
 * notification ({@link ApprovalNotifications}), and the woken task re-runs
 * {@link #check}: an explicit grant or denial wins — that is what makes
 * "Allow once" possible — then the session's standing rules, which a
 * session-wide "allow" has just extended.
 */
public final class ParkingApprovalGate implements ApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(ParkingApprovalGate.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolApprovalRepository store;
    private final AgentSessionService sessions;
    private final ConversationManager conversations;
    private final SessionChannels sessionChannels;
    private final UserChannels userChannels;
    private final TaskQueue queue;

    public ParkingApprovalGate(ToolApprovalRepository store, AgentSessionService sessions,
                               ConversationManager conversations, SessionChannels sessionChannels,
                               UserChannels userChannels, TaskQueue queue) {
        this.store = store;
        this.sessions = sessions;
        this.conversations = conversations;
        this.sessionChannels = sessionChannels;
        this.userChannels = userChannels;
        this.queue = queue;
    }

    /**
     * Decides, in order: an explicit answer from the human (delivered as a
     * task notification — the only way "Allow once"/"Deny" can work without
     * a standing rule), then the standing rules (the agent's flag minus the
     * session chain's approvals — "allow for this session" at the root covers
     * every sub-agent), and only then parking: one store entry per open
     * question (idempotent), one live card push, {@code WAIT}.
     */
    @Override
    public Verdict check(TaskContext task, AgentSession session, AgentDefinition def,
                         String callId, String toolName, Map<String, Object> arguments) {
        for (var notification : task.notifications()) {
            Boolean decision = ApprovalNotifications.decision(notification);
            if (decision != null) {
                // The card lives in the root chat, and the store is keyed by it.
                store.delete(sessions.rootSession(session.id()).id(), callId);
                return decision ? Verdict.PROCEED : Verdict.DENIED;
            }
        }
        boolean flagged = def.tools().stream()
                .anyMatch(t -> toolName.equals(t.name()) && t.enabled() && t.needsApproval());
        if (!flagged) {
            // Only a flagged call ever registers a card — no session lookups for the rest.
            return Verdict.PROCEED;
        }
        AgentSession root = sessions.rootSession(session.id());
        if (sessions.isToolApproved(session.id(), toolName)) {
            store.delete(root.id(), callId);   // no stale card outlives the decision
            return Verdict.PROCEED;
        }
        var entry = new ToolApproval(
                task.task().id(), callId, toolName, approvalContent(toolName, arguments),
                session.id(), root.id(), task.task().id(), Instant.now());
        if (store.saveIfAbsent(entry)) {
            log.info("Tool '{}' (call {}) waits at the approval gate — card registered for root session {}",
                    toolName, callId, root.id());
            publishApprovalCard(root, entry, arguments);
            // The user's own stream too, so a client that is not looking at
            // this chat — a session list, another tab — learns someone waits.
            userChannels.publish(root.userId(), new UserEvent
                    .ApprovalRequested(root.id(), callId, toolName));
        }
        return Verdict.WAIT;
    }

    @Override
    public List<ToolApproval> open(SessionId rootSessionId) {
        return store.openForRoot(rootSessionId);
    }

    /**
     * A session-wide approval becomes a standing rule first and releases the
     * parked siblings of the same tool with an explicit grant, which keeps it
     * deterministic even if a rule read would race the write.
     */
    @Override
    public boolean answer(SessionId rootSessionId, String callId, boolean approved, ApprovalScope scope) {
        ToolApproval open = store.find(rootSessionId, callId).orElse(null);
        if (open == null) {
            log.warn("No open approval for call {} in session {} — stale card, nothing to answer",
                    callId, rootSessionId);
            return false;
        }
        if (approved && scope == ApprovalScope.SESSION && open.toolName() != null) {
            sessions.approveToolForSession(rootSessionId, open.toolName());
            for (ToolApproval other : store.openForRoot(rootSessionId)) {
                if (other.callId().equals(callId)) continue;
                if (!open.toolName().equals(other.toolName())) continue;
                queue.notify(other.toolTaskId(), ApprovalNotifications.approvalGranted());
                store.delete(rootSessionId, other.callId());
                log.info("Session approval of '{}' released parked call {} as well",
                        open.toolName(), other.callId());
            }
        }
        boolean delivered = queue.notify(open.toolTaskId(), approved
                ? ApprovalNotifications.approvalGranted()
                : ApprovalNotifications.approvalDenied());
        store.delete(rootSessionId, callId);
        if (!delivered) {
            log.warn("Approval for call {} could not be delivered — task {} is gone (restart/cancel)",
                    callId, open.toolTaskId());
            return false;
        }
        log.info("Approval answer for call {} ({}, scope {}) delivered to task {}",
                callId, approved ? "granted" : "denied", scope, open.toolTaskId());
        return true;
    }

    @Override
    public void close(SessionId rootSessionId) {
        store.deleteForRoot(rootSessionId);
    }

    /** The card's payload — same {@code {"name","arguments"}} shape the UI has always parsed. */
    private static String approvalContent(String toolName, Map<String, Object> arguments) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "name", toolName, "arguments", arguments == null ? Map.of() : arguments));
        } catch (Exception e) {
            return "{\"name\":\"" + toolName + "\",\"arguments\":{}}";
        }
    }

    /**
     * The live mirror of the registered card, pushed on the ROOT turn's
     * channel — the suspended chat stream shows the card at once instead of
     * waiting for a reload. Best-effort: the store still renders it.
     */
    private void publishApprovalCard(AgentSession root, ToolApproval entry, Map<String, Object> arguments) {
        try {
            var rootHistory = conversations.loadCompleteHistory(root.conversationId());
            ChatTurnId rootTurnId = rootHistory.currentTurnId().orElse(null);
            if (rootTurnId == null) return;
            sessionChannels.publisherFor(root.id(), rootTurnId, rootHistory.currentRun())
                    .accept(new StreamEvent.ApprovalRequested(
                    entry.requestId(), entry.callId(), entry.toolName(),
                    arguments == null ? Map.of() : arguments,
                    entry.originSessionId(), entry.toolTaskId()));
        } catch (Exception e) {
            log.warn("Live approval card push failed (the store still renders it on reload): {}",
                    e.getMessage());
        }
    }
}
