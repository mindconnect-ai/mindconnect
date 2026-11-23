package ai.mindconnect.agent.runtime.engine.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.InterruptionAnswer;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.taskqueue.SharedStateStore;
import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.TaskRecord;

/**
 * {@link TurnService} on the task queue: a turn is a task, each of its tool
 * calls a child task, each sub-agent a child turn task. This class only
 * puts work on the queue and reads what the queue and the session store
 * say — it runs nothing itself and keeps no turn anywhere, so any instance
 * can answer for a turn another one's workers run.
 *
 * <p>The turns of one session run one after the other, in the order they
 * were submitted ({@link SessionTurnOrder}) — a message a sub-session sends
 * to its parent is such a turn and waits its turn like any other.
 *
 * <p>A turn that has to ask comes back: its task parks, the turn reads as
 * {@link TurnStatus#INTERRUPTED} with its interruptions, and nothing
 * happens until {@link #resume} hands the answers to the tasks that wait.
 *
 * <p>What only this object knows is what the turns it ran said — events are
 * heard in the process whose workers published them.
 */
public final class DefaultTurnService implements TurnService {

    private final TurnAcceptor acceptor;
    private final SessionStore sessionStore;
    private final TaskQueue queue;
    private final TurnTaskMapper turns;
    private final NamespaceProvider namespaces;
    private final SessionTurnOrder order;
    private final TurnEventHub events;
    private final SessionEventHub sessionEvents;

    /**
     * @param sharedState where the order of a session's turns is kept — the
     *                    queue's shared state, so the order holds for every
     *                    process that works on the queue
     * @param events      where the turns report; the same hubs the workers
     *                    publish to, which is where subscribers are heard
     */
    DefaultTurnService(TurnAcceptor acceptor, SessionStore sessionStore, TaskQueue queue,
                       SharedStateStore sharedState, EventDelivery events, NamespaceProvider namespaces) {
        this.acceptor = acceptor;
        this.sessionStore = sessionStore;
        this.queue = queue;
        this.turns = new TurnTaskMapper(queue, namespaces);
        this.namespaces = namespaces;
        this.order = new SessionTurnOrder(sharedState);
        this.events = events.toTurnListeners();
        this.sessionEvents = events.toSessionListeners();
    }

    @Override
    public TurnId submit(TurnRequest request) {
        TurnAcceptor.Accepted accepted = acceptor.accept(request);
        Turn turn = accepted.turn();
        // Noted here, where the caller's namespace is current; the workers that run the turn bind it again.
        String namespace = namespaces.current();
        queue.submit(TurnTaskMapper.submission(accepted,
                order.enqueue(namespace, turn.sessionId(), turn.id()), namespace));
        return turn.id();
    }

    @Override
    public Optional<Turn> find(TurnId turnId) {
        return turns.turn(turnId);
    }

    @Override
    public List<TurnMessage> messages(TurnId turnId) {
        return turns.turn(turnId)
                .map(turn -> sessionStore.messages(turn.sessionId(), turnId))
                .orElse(List.of());
    }

    @Override
    public void resume(TurnId turnId, List<InterruptionAnswer> answers) {
        Turn turn = turns.turn(turnId).orElseThrow(() ->
                new IllegalArgumentException("There is no turn " + turnId));
        // All of them looked up first: an answer that fits nothing must not
        // leave the ones before it half delivered.
        List<Interruption> answered = answers.stream()
                .map(answer -> turn.interruptions().stream()
                        .filter(open -> open.id().equals(answer.interruptionId())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Turn " + turnId + " has no open interruption " + answer.interruptionId())))
                .toList();
        for (int i = 0; i < answers.size(); i++) {
            // To the task of the turn that asked — a sub-agent's when the
            // call is one of its; the turns above it are woken as it ends.
            queue.notify(answered.get(i).turnId().value(), TurnWorker.interruptionAnswer(answers.get(i)));
        }
    }

    @Override
    public Subscription subscribe(TurnId turnId, TurnEventListener listener) {
        return events.subscribe(turnId, listener);
    }

    @Override
    public void cancel(TurnId turnId) {
        List<Turn> parked = parkedTurns(turnId);
        queue.cancel(turnId.value());       // walks the task tree: tool calls and sub-agents with it
        // A running turn reports its own end. A parked one has no worker
        // left to do it.
        for (Turn turn : parked) {
            sessionStore.find(turn.sessionId()).ifPresent(session -> sessionEvents.publish(session,
                    new SessionEvent.TurnFinished(turn.sessionId(), turn.id(), TurnStatus.CANCELLED)));
        }
    }

    /** The turn and the sub-agent turns under it that no worker is running right now. */
    private List<Turn> parkedTurns(TurnId turnId) {
        List<Turn> parked = new ArrayList<>();
        turns.turn(turnId)
                .filter(turn -> turn.status() != TurnStatus.RUNNING && !turn.status().isFinal())
                .ifPresent(parked::add);
        // Tool calls lie between a turn and the turns below it.
        for (TaskRecord call : queue.children(turnId.value())) {
            for (TaskRecord child : queue.children(call.id())) {
                if (TurnTaskMapper.TYPE.equals(child.type())) {
                    parked.addAll(parkedTurns(TurnId.of(child.id())));
                }
            }
        }
        return parked;
    }
}
