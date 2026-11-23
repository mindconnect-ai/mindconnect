package ai.mindconnect.agent.runtime.engine.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.mindconnect.agent.loop.domain.TurnEvent;
import ai.mindconnect.agent.loop.domain.TurnOutcome;
import ai.mindconnect.agent.loop.port.in.AgentLoop;
import ai.mindconnect.agent.loop.port.in.TurnContext;
import ai.mindconnect.agent.loop.service.DefaultAgentLoop;
import ai.mindconnect.agent.loop.service.DefaultAgentRound;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.InterruptionAnswer;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.hook.AgentContext;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.taskqueue.TaskContext;
import ai.mindconnect.taskqueue.TaskNotification;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskWorker;

/**
 * Runs a turn as a task. One execution turns the agent loop as far as it
 * goes; when the loop stops to wait, the task parks — holding no thread —
 * and the queue wakes it: when its tool calls and sub-agents are done, or
 * when a message arrives. A turn that is interrupted parks the same way and
 * is woken by the caller's answers.
 *
 * <p>The task is the turn's only record ({@link TurnTaskMapper}). What the
 * turn has said is in the session; what it has decided about its calls is
 * in the task's state ({@link CallDecisions}); everything else is looked up
 * again on every wake, so a turn can go on wherever a worker picks it up.
 */
final class TurnWorker implements TaskWorker {

    private static final String KIND = "kind";
    /** A notification from the caller: an answer to an interruption. */
    private static final String INTERRUPTION_ANSWER = "interruption-answer";

    private final SessionStore sessions;
    private final ToolResolver tools;
    private final ModelGateway gateway;
    private final HookRunner hooks;
    private final EventDelivery events;
    private final TurnTaskMapper turns;
    private final InstructionRenderer instructions;

    TurnWorker(SessionStore sessions, ToolResolver tools, ModelGateway gateway, HookRunner hooks,
               EventDelivery events, TurnTaskMapper turns, InstructionRenderer instructions) {
        this.sessions = sessions;
        this.tools = tools;
        this.gateway = gateway;
        this.hooks = hooks;
        this.events = events;
        this.turns = turns;
        this.instructions = instructions;
    }

    /** The caller's answer to an interruption, as a message to the task of the turn that asked. */
    static TaskNotification interruptionAnswer(InterruptionAnswer answer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(KIND, INTERRUPTION_ANSWER);
        payload.put("interruptionId", answer.interruptionId());
        payload.put("answer", answer.answer());
        return TaskNotification.from("caller", payload);
    }

    @Override
    public TaskOutcome execute(TaskContext ctx) {
        // One turn of a session at a time: this one stands behind the turn
        // submitted before it and is woken when that one has ended.
        var ahead = turns.stillAhead(ctx.task());
        if (ahead.isPresent()) {
            return TaskOutcome.suspendUntil(ahead.get().value());
        }
        Turn turn = turns.turn(ctx.task());
        TurnId turnId = turn.id();
        Session session = sessions.find(turn.sessionId()).orElseThrow();
        CallDecisions decisions = CallDecisions.fromState(ctx.task().state().get(TurnTaskMapper.DECISIONS));

        applyAnswers(ctx, decisions, turn, session);

        AgentContext agentContext = new AgentContext(session, turn.agent(), turnId);
        AgentLoop loop = new DefaultAgentLoop(
                new DefaultAgentRound(
                        new GatewayLlmProvider(gateway, tools, hooks, agentContext, instructions),
                        new QueuedToolExecutor(ctx, decisions, turn, session)),
                turn.agent().maxRounds());
        ctx.onCancel(() -> loop.cancel(turnId.value()));
        SessionEventTranslator sessionEvents =
                new SessionEventTranslator(events.toSessionListeners(), sessions, session, turn);
        TurnContext context = new TurnContext(turnId.value(), turn.sessionId().value(),
                new StoredTurnJournal(sessions, turn.sessionId(), ctx::cancelRequested),
                event -> {
                    publish(ctx.task(), event);
                    sessionEvents.translate(event);
                }, List::of, List.of(new AnswerHookAdvisor(hooks, agentContext)));

        // Whether the turn has begun is what the session says, not how often
        // its task was woken: a turn that waited in line was woken and has
        // still not said a word.
        TurnOutcome outcome;
        try {
            outcome = sessions.messages(turn.sessionId(), turnId).isEmpty()
                    ? loop.start(context, TurnTaskMapper.userMessage(ctx.task()))
                    : loop.resume(context);
        } catch (RuntimeException e) {
            // The loop reports the end of a turn it ends itself. A turn that
            // dies under it ends too, and whoever follows the session must
            // hear that — as a cancel when one closed the model call.
            TurnStatus status = ctx.cancelRequested() ? TurnStatus.CANCELLED : TurnStatus.FAILED;
            sessionEvents.finished(status);
            ended(agentContext, turn, status, null, e.getMessage());
            throw e;
        }

        if (outcome.waitsForTools()) {
            List<Interruption> asked = decisions.interruptions(turnId, session.id());
            if (!asked.isEmpty()) {
                sessionEvents.interrupted(asked);
            }
            return park(ctx, decisions, outcome);
        }
        ctx.updateState(TurnTaskMapper.finishedState(statusOf(outcome), outcome));
        ended(agentContext, turn, statusOf(outcome), outcome, null);
        return TaskOutcome.done(outcome.text());
    }

    /** Lets whoever listens know the turn is over. The turn has ended by then; nothing here can change that. */
    private void ended(AgentContext context, Turn turn, TurnStatus status, TurnOutcome outcome, String error) {
        Turn ended = new Turn(turn.id(), turn.sessionId(), turn.agent(), status,
                List.of(), outcome, error);
        hooks.run(RuntimeHook.TURN_END, context, asked -> ended);
    }

    /**
     * The turn waits, and its task parks. What it has to ask needs no
     * telling: whoever looks at the turn, or at a turn above it, reads the
     * open interruptions from the parked tasks.
     */
    private static TaskOutcome park(TaskContext ctx, CallDecisions decisions, TurnOutcome outcome) {
        ctx.updateState(Map.of(TurnTaskMapper.DECISIONS, decisions.toState()));
        // A turn that waits for nothing but answers from outside is parked
        // until it is told. Every other turn is parked on its children — and
        // the queue decides whether there is one left to wait for: a tool
        // that finished between the loop's last look and this line means
        // the task is requeued at once, where "wait for a message" would
        // let it sleep through a result that is already there.
        boolean onlyAnswers = outcome.waitingFor().stream().allMatch(decisions::isInterrupted);
        return onlyAnswers ? TaskOutcome.suspendUntilNotified() : TaskOutcome.suspendUntilChildren();
    }

    /**
     * What the caller answered since the turn was parked. An answer to a
     * tool approval runs the call again and hands it the answer — what the
     * answer means is for the hook that asked. An answer to anything else —
     * a question, a hand-off — is the call's result.
     */
    @SuppressWarnings("unchecked")
    private void applyAnswers(TaskContext ctx, CallDecisions decisions, Turn turn, Session session) {
        for (TaskNotification notification : ctx.notifications()) {
            if (!INTERRUPTION_ANSWER.equals(notification.payload().get(KIND))) {
                continue;
            }
            String interruptionId = (String) notification.payload().get("interruptionId");
            Map<String, Object> answer = (Map<String, Object>) notification.payload().get("answer");
            Interruption interruption = decisions.answered(interruptionId, turn.id(), session.id()).orElse(null);
            if (interruption == null) {
                continue;       // answered twice, or never asked: nothing waits under this id
            }
            if (Interruption.TOOL_APPROVAL.equals(interruption.kind())) {
                QueuedToolExecutor.startTool(ctx, interruption.call(),
                        Optional.of(new InterruptionAnswer(interruptionId, answer)));
            } else {
                decisions.resultFromAnswer(interruptionId,
                        answer.get(InterruptionAnswer.TEXT) instanceof String said ? said : "");
            }
        }
    }

    /** Tells the turn's own listeners, and those of every turn it runs under. */
    private void publish(TaskRecord task, TurnEvent event) {
        events.toTurnListeners().publish(TurnId.of(task.id()), event);
        String parentId = task.parentTaskId();
        while (parentId != null) {
            events.toTurnListeners().publish(TurnId.of(parentId), event);
            parentId = turns.parentOf(parentId);
        }
    }

    private static TurnStatus statusOf(TurnOutcome outcome) {
        return switch (outcome.status()) {
            case COMPLETED -> TurnStatus.COMPLETED;
            case INCOMPLETE -> TurnStatus.INCOMPLETE;
            case CANCELLED -> TurnStatus.CANCELLED;
            case WAITING_FOR_TOOLS -> TurnStatus.WAITING;
        };
    }
}
