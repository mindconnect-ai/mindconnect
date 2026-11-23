package ai.mindconnect.agent.starter.runtime.bridge;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.hook.AgentContext;
import ai.mindconnect.agent.runtime.api.hook.ToolInvocation;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.chat.stream.ToolApproval;
import ai.mindconnect.agent.runtime.port.out.ToolApprovalRepository;

/**
 * Keeps the approvals a chat is waiting for where the chat page looks for
 * them. The runtime holds an open approval in the turn's task; the page
 * draws its cards after a reload, and marks a waiting chat in its list,
 * from the approval repository — an entry is a card.
 *
 * <p>Written where the tool call is stopped and not from the stream of a
 * turn, so the entry is there whoever is or is not listening, and under the
 * chat at the top: a sub-agent's question shows in the chat that called it.
 */
final class OpenApprovalRecorder {

    private final ToolApprovalRepository approvals;
    private final Supplier<SessionStore> sessions;
    private final ObjectMapper json = new ObjectMapper();

    OpenApprovalRecorder(ToolApprovalRepository approvals, Supplier<SessionStore> sessions) {
        this.approvals = approvals;
        this.sessions = sessions;
    }

    /** A tool call has been stopped or has run: an entry for a call that asks, none for one that was answered. */
    void called(ToolInvocation invocation, ToolOutput output) {
        SessionId root = SessionId.of(rootOf(invocation.context().session()).id().value());
        String callId = invocation.call().callId();
        if (output instanceof ToolOutput.Interrupted asked && Interruption.TOOL_APPROVAL.equals(asked.kind())) {
            approvals.saveIfAbsent(new ToolApproval(callId, callId, invocation.call().toolName(),
                    content(invocation), SessionId.of(invocation.context().session().id().value()), root, callId,
                    Instant.now()));
        } else if (invocation.answer().isPresent()) {
            approvals.delete(root, callId);
        }
    }

    /** A turn of a chat is over, however: nothing of it can still be waiting. */
    void ended(AgentContext context) {
        if (context.session().parentId() == null) {
            approvals.deleteForRoot(SessionId.of(context.session().id().value()));
        }
    }

    private Session rootOf(Session session) {
        Session current = session;
        while (current.parentId() != null) {
            Session parent = sessions.get().find(current.parentId()).orElse(null);
            if (parent == null) {
                break;
            }
            current = parent;
        }
        return current;
    }

    /** The call as the page's card reads it: its name and its arguments. */
    private String content(ToolInvocation invocation) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("name", invocation.call().toolName());
        call.put("arguments", invocation.call().arguments());
        try {
            return json.writeValueAsString(call);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A tool call cannot be written as JSON", e);
        }
    }
}
