package ai.mindconnect.agent.runtime.service.approval;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.ToolApproval;
import ai.mindconnect.taskqueue.TaskContext;

import java.util.List;
import java.util.Map;

/**
 * Where a tool call that needs a human's yes waits for it. The tool-call
 * worker asks right before a tool runs; the chat service asks which questions
 * are open, hands over the human's answer and closes the questions of a turn
 * that ends. How a call is parked, where its question is kept and how the
 * answer wakes it stays behind this port, so a runtime can bring its own gate.
 */
public interface ApprovalGate {

    /** What the worker does with the call. */
    enum Verdict { PROCEED, WAIT, DENIED }

    /**
     * Decides a call in the task that runs it. {@link Verdict#WAIT} parks the
     * task until the answer arrives as one of its notifications; the woken
     * task asks again.
     */
    Verdict check(TaskContext task, AgentSession session, AgentDefinition def,
                  String callId, String toolName, Map<String, Object> arguments);

    /** The questions open in this chat, oldest first. */
    List<ToolApproval> open(SessionId rootSessionId);

    /**
     * Delivers the human's answer to the parked call.
     *
     * @return false when this chat has no such open question or its call is gone
     */
    boolean answer(SessionId rootSessionId, String callId, boolean approved, ApprovalScope scope);

    /** Drops every open question of this chat: its turn was cancelled or superseded. */
    void close(SessionId rootSessionId);
}
