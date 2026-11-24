package ai.mindconnect.agent.runtime.engine.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.TurnOutcome;
import ai.mindconnect.agent.loop.domain.Usage;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;
import ai.mindconnect.taskqueue.TaskSubmission;

/**
 * A turn and its task are the same thing seen twice — this class is the one
 * place that knows how. The task's id is the turn's id. What a turn IS and
 * never changes is in the task's payload: its session, the agent and the
 * model settings it was accepted with. How it stands is the task itself:
 * its status, the interruptions in its state, the outcome it ended with.
 *
 * <p>So there is no record of a turn next to its task that could disagree
 * with it: {@link #turn} reads the task as it is at that moment.
 */
final class TurnTaskMapper {

    static final String TYPE = "turn";

    private static final AgentDefinitionConverter AGENTS = new AgentDefinitionConverter();

    private static final String SESSION_ID = "sessionId";
    private static final String USER_MESSAGE = "userMessage";
    private static final String AGENT = "agent";
    /** Payload: the turn of the same session this one has to wait for; absent for the first. */
    private static final String AFTER = "after";

    /** State: what the turn decided about its calls, kept across parking. */
    static final String DECISIONS = "callDecisions";
    /** State of a finished turn task: how its turn ended. */
    static final String STATUS = "turnStatus";
    private static final String OUTCOME = "outcome";

    private static final TurnMessageConverter MESSAGES = new TurnMessageConverter();

    private final TaskQueue queue;
    private final NamespaceProvider namespaces;

    TurnTaskMapper(TaskQueue queue, NamespaceProvider namespaces) {
        this.queue = queue;
        this.namespaces = namespaces;
    }

    // ── a turn as a task ────────────────────────────────────────────────────

    /** The task that runs this turn, the first of its session. */
    /**
     * The task that runs this turn.
     *
     * @param after  the turn of the same session that has to end first
     * @param caller in whose namespace and as whom the turn runs, see {@link CallerBoundWorker}
     */
    static TaskSubmission submission(TurnAcceptor.Accepted accepted, Optional<TurnId> after,
                                     Map<String, Object> caller) {
        Turn turn = accepted.turn();
        Map<String, Object> payload = new LinkedHashMap<>();
        after.ifPresent(previous -> payload.put(AFTER, previous.value()));
        payload.putAll(caller);
        payload.put(SESSION_ID, turn.sessionId().value());
        payload.put(USER_MESSAGE, MESSAGES.toJson(accepted.userMessage()));
        payload.put(AGENT, AGENTS.toJson(turn.agent()));
        return TaskSubmission.of(TYPE, payload).withId(turn.id().value());
    }

    /** The name of the agent the task's turn runs; {@code null} for a payload without one. */
    static String agentName(TaskRecord task) {
        return task.payload().get(AGENT) instanceof Map<?, ?> agent && agent.get("name") instanceof String name
                ? name : null;
    }

    @SuppressWarnings("unchecked")
    static TurnMessage userMessage(TaskRecord task) {
        return MESSAGES.fromJson((Map<String, Object>) task.payload().get(USER_MESSAGE));
    }

    /** The turn this one waits for, while that one has not ended; empty once the way is free. */
    Optional<TurnId> stillAhead(TaskRecord task) {
        return Optional.ofNullable((String) task.payload().get(AFTER))
                .filter(previous -> queue.get(previous).map(ahead -> !ahead.status().terminal()).orElse(false))
                .map(TurnId::of);
    }

    /** What a finished turn task keeps in its state, next to what it decided about its calls. */
    static Map<String, Object> finishedState(TurnStatus status, TurnOutcome outcome) {
        Map<String, Object> outcomeJson = new LinkedHashMap<>();
        outcomeJson.put("status", outcome.status().name());
        outcomeJson.put("incompleteReason",
                outcome.incompleteReason() == null ? null : outcome.incompleteReason().name());
        outcomeJson.put("inputTokens", outcome.usage().inputTokens());
        outcomeJson.put("outputTokens", outcome.usage().outputTokens());
        outcomeJson.put("rounds", outcome.rounds());
        outcomeJson.put("text", outcome.text());
        return Map.of(STATUS, status.name(), OUTCOME, outcomeJson);
    }

    /** The task above this one, or {@code null} at the top: events of a turn are heard on every turn above it. */
    String parentOf(String taskId) {
        return queue.get(taskId).map(TaskRecord::parentTaskId).orElse(null);
    }

    // ── a task as a turn ────────────────────────────────────────────────────

    /** The turn as its task stands right now; empty when no turn task has this id. */
    Optional<Turn> turn(TurnId turnId) {
        // A turn of another namespace does not exist for whoever asks from here, however it came by the id.
        return queue.get(turnId.value())
                .filter(task -> TYPE.equals(task.type()))
                .filter(task -> namespaces.current().equals(task.payload().get(CallerBoundWorker.NAMESPACE)))
                .map(this::turn);
    }

    @SuppressWarnings("unchecked")
    Turn turn(TaskRecord task) {
        TurnId turnId = TurnId.of(task.id());
        SessionId sessionId = SessionId.of((String) task.payload().get(SESSION_ID));
        List<Interruption> interruptions = interruptions(task);
        return new Turn(turnId, callerOf(task), sessionId,
                AGENTS.fromJson((Map<String, Object>) task.payload().get(AGENT)),
                status(task, interruptions),
                interruptions,
                task.state().get(OUTCOME) instanceof Map<?, ?> outcome
                        ? outcomeFromJson((Map<String, Object>) outcome) : null,
                task.status() == TaskStatus.FAILED && !task.cancelRequested() ? task.failure().message() : null);
    }

    /** The turn that called this one as a sub-agent, or {@code null}: the nearest turn task above it. */
    private TurnId callerOf(TaskRecord task) {
        // A turn a tool handed its call to hangs under that call's task.
        Optional<TaskRecord> above = Optional.ofNullable(task.parentTaskId()).flatMap(queue::get);
        while (above.isPresent() && !TYPE.equals(above.get().type())) {
            above = Optional.ofNullable(above.get().parentTaskId()).flatMap(queue::get);
        }
        return above.map(caller -> TurnId.of(caller.id())).orElse(null);
    }

    /** The turn of the same session this one was lined up behind; empty for the session's first. */
    Optional<TurnId> before(TurnId turnId) {
        return queue.get(turnId.value())
                .map(task -> (String) task.payload().get(AFTER))
                .map(TurnId::of);
    }

    /**
     * What the turn has to ask right now: its own open interruptions and
     * those of the sub-agent turns under it. Read from the tasks at this
     * moment — a turn that has been answered and woken is no longer parked,
     * and what it asked is no longer open, whatever its state still lists.
     */
    private List<Interruption> interruptions(TaskRecord task) {
        List<Interruption> interruptions = new ArrayList<>();
        if (task.status() == TaskStatus.SUSPENDED) {
            interruptions.addAll(CallDecisions.fromState(task.state().get(DECISIONS)).interruptions(
                    TurnId.of(task.id()), SessionId.of((String) task.payload().get(SESSION_ID))));
        }
        // A turn a tool handed its call to hangs under that call's task, so
        // the way down leads through tasks that are no turns.
        for (TaskRecord child : queue.children(task.id())) {
            if (!child.status().terminal()) {
                interruptions.addAll(TYPE.equals(child.type()) ? interruptions(child) : below(child));
            }
        }
        return interruptions;
    }

    private List<Interruption> below(TaskRecord task) {
        List<Interruption> interruptions = new ArrayList<>();
        for (TaskRecord child : queue.children(task.id())) {
            if (!child.status().terminal()) {
                interruptions.addAll(TYPE.equals(child.type()) ? interruptions(child) : below(child));
            }
        }
        return interruptions;
    }

    private static TurnStatus status(TaskRecord task, List<Interruption> interruptions) {
        return switch (task.status()) {
            // A turn that was woken waits in the queue for a worker; it has long started.
            case QUEUED -> task.startedAt() == null && task.attempt() == 0 ? TurnStatus.QUEUED : TurnStatus.RUNNING;
            case RUNNING -> TurnStatus.RUNNING;
            // Parked before it ever ran: it stands in its session's line.
            case SUSPENDED -> task.state().isEmpty() ? TurnStatus.QUEUED
                    : interruptions.isEmpty() ? TurnStatus.WAITING : TurnStatus.INTERRUPTED;
            case COMPLETED -> TurnStatus.valueOf((String) task.state().get(STATUS));
            case CANCELLED -> TurnStatus.CANCELLED;
            // A cancel closes the connection under a running model call; what
            // fails then is the cancel arriving, not the turn.
            case FAILED -> task.cancelRequested() ? TurnStatus.CANCELLED : TurnStatus.FAILED;
        };
    }

    // ── values as data ──────────────────────────────────────────────────────

    private static TurnOutcome outcomeFromJson(Map<String, Object> json) {
        return new TurnOutcome(
                TurnOutcome.Status.valueOf((String) json.get("status")),
                json.get("incompleteReason") == null ? null
                        : TurnOutcome.IncompleteReason.valueOf((String) json.get("incompleteReason")),
                new Usage(((Number) json.get("inputTokens")).longValue(), ((Number) json.get("outputTokens")).longValue()),
                ((Number) json.get("rounds")).intValue(), Set.of(), (String) json.get("text"));
    }
}
