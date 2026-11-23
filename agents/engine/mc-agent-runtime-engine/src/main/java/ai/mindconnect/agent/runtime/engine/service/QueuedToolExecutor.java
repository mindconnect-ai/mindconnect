package ai.mindconnect.agent.runtime.engine.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.mindconnect.agent.loop.domain.ToolCalls;
import ai.mindconnect.agent.loop.domain.ToolResult;
import ai.mindconnect.agent.loop.port.out.AgentRoundToolExecutor;
import ai.mindconnect.agent.runtime.api.domain.InterruptionAnswer;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.taskqueue.TaskContext;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;

/**
 * The loop's tool executor on the task queue: starting a call submits a
 * child task and returns, and the state of a call is the state of its task.
 * A turn therefore never holds a thread while its tools run — it parks, and
 * the queue wakes it.
 *
 * <p>Every call the model asks for becomes a task; whether the tool exists,
 * may run, or hands its call on is decided where the task runs
 * ({@link ToolCallWorker}). What is kept here, in {@link CallDecisions},
 * is only what a task cannot hold: that a call waits for an answer from
 * outside, and what the answer was.
 */
final class QueuedToolExecutor implements AgentRoundToolExecutor {

    private final TaskContext ctx;
    private final CallDecisions decisions;
    private final Turn turn;
    private final Session session;

    QueuedToolExecutor(TaskContext ctx, CallDecisions decisions, Turn turn, Session session) {
        this.ctx = ctx;
        this.decisions = decisions;
        this.turn = turn;
        this.session = session;
    }

    @Override
    public void execute(String requestId, String sessionId, ToolCalls.Call call) {
        if (decisions.knows(call.callId()) || childOf(call.callId()).isPresent()) {
            return;     // started or decided before: a call is not started twice
        }
        startTool(ctx, new ToolCall(call.callId(), call.name(), call.arguments(), turn.id(), session.id()),
                Optional.empty());
    }

    /**
     * Submits the tool call as a child task of the turn's task.
     *
     * @param answer what the user said to the call's tool approval, when
     *               the call is run again because of it
     */
    static void startTool(TaskContext ctx, ToolCall call, Optional<InterruptionAnswer> answer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(NamespaceBoundWorker.NAMESPACE, ctx.task().payload().get(NamespaceBoundWorker.NAMESPACE));
        payload.put(ToolCallWorker.TURN_ID, call.turnId().value());
        payload.put(ToolCallWorker.SESSION_ID, call.sessionId().value());
        payload.put(ToolCallWorker.CALL_ID, call.callId());
        payload.put(ToolCallWorker.TOOL_NAME, call.toolName());
        payload.put(ToolCallWorker.ARGUMENTS, call.arguments());
        answer.ifPresent(given -> payload.put(ToolCallWorker.ANSWER, given.answer()));
        ctx.submitChild(ToolCallWorker.TYPE, payload);
    }

    @Override
    public ToolResult result(String sessionId, String callId) {
        Optional<String> answer = decisions.resultFromAnswer(callId);
        if (answer.isPresent()) {
            return new ToolResult.Finished(answer.get(), false, 0);
        }
        if (decisions.isInterrupted(callId)) {
            return new ToolResult.Running();
        }
        return childOf(callId).map(child -> resultOf(callId, child))
                .orElseGet(() -> new ToolResult.Lost("no task was ever started for the call"));
    }

    @SuppressWarnings("unchecked")
    private ToolResult resultOf(String callId, TaskRecord child) {
        if (child.status() == TaskStatus.COMPLETED && child.state().containsKey(ToolCallWorker.INTERRUPT_KIND)) {
            // The call asked instead of answering: from here on it waits for
            // an answer from outside, not for a task.
            decisions.interrupt(
                    new ToolCall(callId, (String) child.payload().get(ToolCallWorker.TOOL_NAME),
                            (Map<String, Object>) child.payload().get(ToolCallWorker.ARGUMENTS),
                            turn.id(), session.id()),
                    (String) child.state().get(ToolCallWorker.INTERRUPT_KIND),
                    (Map<String, Object>) child.state().get(ToolCallWorker.INTERRUPT_DETAILS));
            return new ToolResult.Running();
        }
        return switch (child.status()) {
            case QUEUED, RUNNING, SUSPENDED -> new ToolResult.Running();
            case FAILED -> new ToolResult.Finished("Error: " + child.failure().message(), true, 0);
            case CANCELLED -> new ToolResult.Lost("the call was cancelled");
            case COMPLETED -> new ToolResult.Finished(child.result(),
                    Boolean.TRUE.equals(child.state().get(ToolCallWorker.FAILED)), 0);
        };
    }

    /**
     * The task that runs this call. A call that was interrupted for an
     * approval and answered has two — the one that asked and the one that
     * runs with the answer — and the later one speaks for the call.
     */
    private Optional<TaskRecord> childOf(String callId) {
        List<TaskRecord> tasks = ctx.children().stream()
                .filter(child -> callId.equals(child.payload().get(ToolCallWorker.CALL_ID)))
                .toList();
        return tasks.stream().filter(task -> task.payload().containsKey(ToolCallWorker.ANSWER)).findFirst()
                .or(() -> tasks.stream().findFirst());
    }

    @Override
    public void cancel(String requestId) {
        // Cancelling the turn's task cancels its children: the queue walks the tree.
    }
}
