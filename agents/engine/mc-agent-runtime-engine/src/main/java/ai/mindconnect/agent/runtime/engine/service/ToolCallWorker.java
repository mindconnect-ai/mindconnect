package ai.mindconnect.agent.runtime.engine.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.mindconnect.agent.runtime.api.domain.InterruptionAnswer;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
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
    /** In the task's state: the agents its call was handed to, in the order their turns were started. */
    private static final String DELEGATED_TO = "delegatedTo";
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
            return delegatedResult(ctx, delegated);
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
                invocation -> run(invocation.call(), invocation.context().agent(), session));
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
            // Accepted one by one before any is started: a request that is refused starts none.
            List<TurnAcceptor.Accepted> accepted = handedOn.requests().stream().map(acceptor::accept).toList();
            ctx.updateState(Map.of(DELEGATED_TO,
                    accepted.stream().map(turn -> turn.turn().agent().name()).toList()));
            accepted.forEach(turn -> ctx.submitChild(TurnTaskMapper.submission(turn, Optional.empty(),
                    CallerBoundWorker.callerOf(ctx.task()))));
            return TaskOutcome.suspendUntilChildren();
        } catch (IllegalArgumentException e) {
            ctx.updateState(Map.of(FAILED, true));
            return TaskOutcome.done("Error: " + e.getMessage());
        }
    }

    /**
     * The turns' answers are the call's result: the one answer as it is,
     * several under the names of their agents. A turn that ended without an
     * answer is an error the model reads; the call failed when all did.
     */
    private TaskOutcome delegatedResult(TaskContext ctx, List<TaskRecord> children) {
        if (children.stream().anyMatch(child -> !child.status().terminal())) {
            return TaskOutcome.suspendUntilChildren();
        }
        List<String> answers = children.stream().map(ToolCallWorker::answerOf).toList();
        if (children.stream().noneMatch(ToolCallWorker::answered)) {
            ctx.updateState(Map.of(FAILED, true));
        }
        if (children.size() == 1) {
            return TaskOutcome.done(answers.get(0));
        }
        List<?> names = ctx.task().state().get(DELEGATED_TO) instanceof List<?> kept ? kept : List.of();
        StringBuilder together = new StringBuilder();
        for (int i = 0; i < answers.size(); i++) {
            together.append(i == 0 ? "" : "\n\n").append("## ").append(i + 1).append(". ")
                    .append(i < names.size() ? names.get(i) : "agent").append("\n").append(answers.get(i));
        }
        return TaskOutcome.done(together.toString());
    }

    private static boolean answered(TaskRecord child) {
        return child.status() == TaskStatus.COMPLETED
                && TurnStatus.COMPLETED.name().equals(child.state().get(TurnTaskMapper.STATUS));
    }

    private static String answerOf(TaskRecord child) {
        if (answered(child)) {
            return child.result();
        }
        Object status = child.status() == TaskStatus.COMPLETED
                ? child.state().get(TurnTaskMapper.STATUS) : child.status();
        return "Error: the agent did not finish its task (" + status + ")";
    }

    private ToolOutput run(ToolCall call, AgentDefinition agent, Session session) {
        Optional<Tool> tool = tools.resolve(call.toolName(), agent, session);
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
