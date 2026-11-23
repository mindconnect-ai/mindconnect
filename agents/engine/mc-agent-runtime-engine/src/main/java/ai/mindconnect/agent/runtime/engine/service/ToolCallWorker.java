package ai.mindconnect.agent.runtime.engine.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.mindconnect.agent.runtime.api.domain.InterruptionAnswer;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.hook.AgentContext;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.hook.ToolInvocation;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.api.port.out.Tool;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.taskqueue.TaskContext;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;
import ai.mindconnect.taskqueue.TaskWorker;

/**
 * Runs one tool call as a task of its own, a child of its turn's task. The
 * payload is data — which turn, which tool, which arguments — and the tool
 * is looked up when the task runs, so the call can run on another worker
 * than the turn that asked for it.
 *
 * <p>The output is the task's result. A failed output is no failed task:
 * the model reads the error and the turn goes on. A call that interrupts
 * completes its task too and leaves the interruption in its state for the
 * turn to pick up. A call the tool hands to another turn keeps its task
 * open: the turn runs as this task's child, and its answer becomes this
 * task's result.
 */
final class ToolCallWorker implements TaskWorker {

    static final String TYPE = "tool-call";
    static final String TURN_ID = "turnId";
    static final String SESSION_ID = "sessionId";
    static final String CALL_ID = "callId";
    static final String TOOL_NAME = "toolName";
    static final String ARGUMENTS = "arguments";
    static final String ANSWER = "answer";
    static final String FAILED = "failed";
    static final String INTERRUPT_KIND = "interruptKind";
    static final String INTERRUPT_DETAILS = "interruptDetails";

    private final SessionStore sessions;
    private final ToolResolver tools;
    private final HookRunner hooks;
    private final TurnTaskMapper turns;
    private final TurnAcceptor acceptor;

    ToolCallWorker(SessionStore sessions, ToolResolver tools, HookRunner hooks, TurnTaskMapper turns,
                   TurnAcceptor acceptor) {
        this.sessions = sessions;
        this.tools = tools;
        this.hooks = hooks;
        this.turns = turns;
        this.acceptor = acceptor;
    }

    @Override
    @SuppressWarnings("unchecked")
    public TaskOutcome execute(TaskContext ctx) {
        List<TaskRecord> delegated = ctx.children();
        if (!delegated.isEmpty()) {
            return delegatedResult(ctx, delegated.get(0));
        }
        Map<String, Object> payload = ctx.task().payload();
        Session session = sessions.find(SessionId.of((String) payload.get(SESSION_ID))).orElseThrow();
        TurnId turnId = TurnId.of((String) payload.get(TURN_ID));
        ToolCall call = new ToolCall((String) payload.get(CALL_ID), (String) payload.get(TOOL_NAME),
                (Map<String, Object>) payload.get(ARGUMENTS), turnId, session.id());
        Optional<InterruptionAnswer> answer = Optional.ofNullable((Map<String, Object>) payload.get(ANSWER))
                .map(given -> new InterruptionAnswer(call.callId(), given));
        Turn turn = turns.turn(turnId).orElseThrow();
        ToolOutput output = hooks.run(RuntimeHook.TOOL_CALL,
                new ToolInvocation(new AgentContext(session, turn.agent(), turnId), call, answer),
                invocation -> run(invocation.call(), session));
        return switch (output) {
            case ToolOutput.Completed completed -> {
                if (completed.failed()) {
                    ctx.updateState(Map.of(FAILED, true));
                }
                yield TaskOutcome.done(completed.text());
            }
            case ToolOutput.Interrupted interrupted -> {
                ctx.updateState(Map.of(INTERRUPT_KIND, interrupted.kind(),
                        INTERRUPT_DETAILS, interrupted.details()));
                yield TaskOutcome.done("");
            }
            case ToolOutput.Delegated handedOn -> delegate(ctx, handedOn);
        };
    }

    private TaskOutcome delegate(TaskContext ctx, ToolOutput.Delegated handedOn) {
        try {
            ctx.submitChild(TurnTaskMapper.submission(acceptor.accept(handedOn.request()), Optional.empty(),
                    (String) ctx.task().payload().get(NamespaceBoundWorker.NAMESPACE)));
            return TaskOutcome.suspendUntilChildren();
        } catch (IllegalArgumentException e) {
            ctx.updateState(Map.of(FAILED, true));
            return TaskOutcome.done("Error: " + e.getMessage());
        }
    }

    /** The turn's answer is the call's result; a turn that ended without one is an error the model reads. */
    private TaskOutcome delegatedResult(TaskContext ctx, TaskRecord child) {
        if (!child.status().terminal()) {
            return TaskOutcome.suspendUntilChildren();
        }
        if (child.status() == TaskStatus.COMPLETED
                && TurnStatus.COMPLETED.name().equals(child.state().get(TurnTaskMapper.STATUS))) {
            return TaskOutcome.done(child.result());
        }
        Object status = child.status() == TaskStatus.COMPLETED
                ? child.state().get(TurnTaskMapper.STATUS) : child.status();
        ctx.updateState(Map.of(FAILED, true));
        return TaskOutcome.done("Error: the agent did not finish its task (" + status + ")");
    }

    private ToolOutput run(ToolCall call, Session session) {
        Optional<Tool> tool = tools.resolve(call.toolName(), session);
        if (tool.isEmpty()) {
            return ToolOutput.failed("Error: there is no tool named " + call.toolName());
        }
        try {
            return tool.get().execute(call, session);
        } catch (RuntimeException e) {
            // A tool that throws must not take the turn with it: the model reads the error and decides.
            return ToolOutput.failed("Error: " + e.getMessage());
        }
    }
}
