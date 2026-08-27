package ai.mindconnect.agent.service.task;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.service.AgentSessionService;
import ai.mindconnect.agent.service.round.AgentRoundToolExecutor;
import ai.mindconnect.agent.service.round.ToolCalls;
import ai.mindconnect.agent.service.round.ToolResult;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.port.in.ConversationManager;
import ai.mindconnect.taskqueue.TaskContext;
import ai.mindconnect.taskqueue.TaskRecord;

import java.util.Optional;
import java.util.UUID;

/**
 * The round's {@link AgentRoundToolExecutor} on the
 * queue (concept 16, step 5): {@link #execute} submits an {@code agent.tool}
 * child task and returns at once; the round then reports
 * {@code WAITING_FOR_TOOLS} and the turn task SUSPENDS on the tool tasks —
 * no thread, no slot, survives a restart. The {@link ToolCallWorker} writes
 * the {@code TOOL_RESULT} into the conversation, and the woken turn's next
 * execution reloads and finds the call closed.
 *
 * <p>Because of that hand-over through the conversation, {@link #result}
 * never carries an output. It answers only the one question the round has
 * about a RUNNING call it cannot see a result for: is the tool still coming?
 * <ul>
 *   <li>Task alive → {@link ToolResult.Running} — keep waiting.</li>
 *   <li>Task terminal AND the result is in the store → {@link ToolResult.Running}
 *       too: suspending on a terminal task requeues immediately, the reload
 *       sees the result, the fold says DONE. Appending it here instead would
 *       write a SECOND result for the same callId.</li>
 *   <li>Task terminal WITHOUT a result (crash before the write, sweep) or no
 *       task at all → {@link ToolResult.Lost} — the round closes the call
 *       with a readable error instead of waiting forever.</li>
 * </ul>
 *
 * <p>Dispatch is idempotent twice over: the fold only dispatches RUNNABLE
 * calls (a TOOL_DISPATCHED marker makes them RUNNING), and the task id is
 * {@code task_tool_<turnId>_<callId>} — a re-submit returns the existing task.
 */
final class QueuedAgentRoundToolExecutor implements AgentRoundToolExecutor {

    private final TaskContext ctx;
    private final ConversationManager conversationManager;
    private final AgentSessionService sessionService;
    private final AgentDefinition def;
    private final AgentSession session;
    private final UUID turnId;
    private final int run;
    private final UUID conversationId;
    private final int depth;

    QueuedAgentRoundToolExecutor(TaskContext ctx, ConversationManager conversationManager,
                                 AgentSessionService sessionService,
                                 AgentDefinition def,
                                 AgentSession session,
                                 UUID turnId, int run, UUID conversationId, int depth) {
        this.ctx = ctx;
        this.conversationManager = conversationManager;
        this.sessionService = sessionService;
        this.def = def;
        this.session = session;
        this.turnId = turnId;
        this.run = run;
        this.conversationId = conversationId;
        this.depth = depth;
    }

    @Override
    public void execute(String requestId, UUID sessionId, ToolCalls.Call call) {
        ctx.submitChild(ToolCallWorker.submission(turnId, run, sessionId, depth, call));
    }

    @Override
    public ToolResult result(UUID sessionId, String callId) {
        String taskId = ToolCallWorker.taskIdFor(turnId, callId);
        Optional<TaskRecord> task = ctx.children().stream()
                .filter(child -> child.id().equals(taskId))
                .findFirst();
        if (task.isEmpty()) {
            return new ToolResult.Lost("no tool task for call " + callId);
        }
        if (!task.get().status().terminal()) {
            return new ToolResult.Running();
        }
        return resultWritten(callId)
                ? new ToolResult.Running()   // reload-after-wake will see it — never append twice
                : new ToolResult.Lost("tool task ended " + task.get().status() + " without a result");
    }

    private boolean resultWritten(String callId) {
        return conversationManager.loadHistory(conversationId, new PageRequest(0, Integer.MAX_VALUE))
                .stream()
                .anyMatch(m -> m.type() == MessageType.TOOL_RESULT
                        && callId.equals(m.metadata().get("callId")));
    }
}
