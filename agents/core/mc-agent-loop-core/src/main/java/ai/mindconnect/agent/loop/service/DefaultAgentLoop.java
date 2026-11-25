package ai.mindconnect.agent.loop.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.mindconnect.agent.loop.domain.RoundOutcome;
import ai.mindconnect.agent.loop.domain.ToolCalls;
import ai.mindconnect.agent.loop.domain.TurnStreamEvent;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.TurnOutcome;
import ai.mindconnect.agent.loop.domain.TurnProgress;
import ai.mindconnect.agent.loop.domain.Usage;
import ai.mindconnect.agent.loop.port.in.AgentLoop;
import ai.mindconnect.agent.loop.port.in.AgentRound;
import ai.mindconnect.agent.loop.port.in.TurnContext;
import ai.mindconnect.agent.loop.port.out.AgentRoundAdvisor;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;

/**
 * {@link AgentLoop} as ordinary code: turns {@link AgentRound} round after
 * round until the model has answered or something is missing.
 *
 * <p>Everything that happens BETWEEN two rounds happens here, and only here:
 * persist, report, check the cancel, let the user steer, count the round
 * brake. The round knows nothing of it — which limits the round to its
 * actual topic: what to do in this one revolution.
 *
 * <p>The loop is not an apparatus. Whoever wants more — compaction,
 * summaries, reviewers — writes it into this loop or around it, instead of
 * slipping it under the round as an advisor.
 *
 * <p>One instance serves every turn. What it remembers of a turn lasts as
 * long as one call: which requests are underway here, and which of those
 * were told to stop.
 */
public final class DefaultAgentLoop implements AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentLoop.class);

    private final AgentRound round;
    private final int maxRounds;
    /** The requests a call of this loop is working on right now. */
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    /** Those of them told to stop — set from any thread, read by the one that runs the turn. */
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    /**
     * @param maxRounds the model rounds a turn may turn before its tool calls
     *                  are refused: the request after that many rounds gets
     *                  an error result per call and the turn ends
     *                  INCOMPLETE:MAX_ROUNDS, for the caller to force an answer
     */
    public DefaultAgentLoop(AgentRound round, int maxRounds) {
        this.round = round;
        this.maxRounds = maxRounds;
    }

    @Override
    public TurnOutcome start(TurnContext turn, TurnMessage userMessage) {
        TurnContext watched = withGuardedListener(turn);
        List<TurnMessage> history = new ArrayList<>(turn.journal().load());
        // A repeated start finds its own question in the conversation and
        // must not ask it twice.
        boolean asked = history.stream().anyMatch(message ->
                message.type() == TurnMessage.Type.USER && turn.requestId().equals(message.turnId()));
        if (!asked) {
            record(watched, history, List.of(userMessage));
        }
        return turnToItsEnd(watched, history);
    }

    @Override
    public TurnOutcome resume(TurnContext turn) {
        TurnContext watched = withGuardedListener(turn);
        return turnToItsEnd(watched, new ArrayList<>(turn.journal().load()));
    }

    @Override
    public void cancel(String requestId) {
        if (!running.contains(requestId)) {
            return;
        }
        // Flag first: the round that comes back from its aborted call must
        // already find the turn cancelled.
        cancelled.add(requestId);
        round.cancel(requestId);
    }

    /**
     * A watcher that throws loses its event, never the turn — also for the
     * events the model call reports through the same listener.
     */
    private static TurnContext withGuardedListener(TurnContext turn) {
        TurnEventListener events = turn.events();
        TurnEventListener guarded = event -> {
            try {
                events.onEvent(event);
            } catch (RuntimeException e) {
                log.warn("Turn event listener failed on {}: {}", event.getClass().getSimpleName(), e.toString());
            }
        };
        return new TurnContext(turn.requestId(), turn.sessionId(), turn.journal(), guarded,
                turn.steering(), turn.advisors());
    }

    /** Runs the rounds as the request underway here and reports how the turn left. */
    private TurnOutcome turnToItsEnd(TurnContext turn, List<TurnMessage> history) {
        String requestId = turn.requestId();
        // A cancel that raced the end of an earlier call of this request is not this call's.
        cancelled.remove(requestId);
        running.add(requestId);
        TurnOutcome outcome;
        try {
            outcome = turnRounds(turn, history);
        } finally {
            running.remove(requestId);
            cancelled.remove(requestId);
        }
        if (!outcome.waitsForTools()) {
            turn.events().onEvent(new TurnStreamEvent.TurnUsage(
                    outcome.usage().inputTokens(), outcome.usage().outputTokens()));
        }
        return outcome;
    }

    private TurnOutcome turnRounds(TurnContext turn, List<TurnMessage> history) {
        String requestId = turn.requestId();
        String sessionId = turn.sessionId();
        List<AgentRoundAdvisor> advisors = turn.advisors();

        // Where the turn stands is the journal's knowledge, not a caller's.
        TurnProgress soFar = turn.journal().progress();
        Usage total = soFar.usage();
        int modelRounds = soFar.modelRounds();

        while (true) {
            if (isCancelled(turn)) {
                log.debug("turn {} cancelled after {} model round(s)", requestId, modelRounds);
                return TurnOutcome.cancelled(total, modelRounds);
            }
            // The model is asked next: the one moment before a model call at
            // which a user message fits. With a call still open it would
            // split the call from its result.
            if (ToolCalls.of(ToolCalls.episode(history)).allDone()) {
                appendSteering(turn, history);
            }

            AgentRoundAdvisor.RoundContext context = new AgentRoundAdvisor.RoundContext(
                    requestId, sessionId, List.copyOf(history));
            // Advisors wrap the round like an onion: the reviewer chain sees
            // (and may rewrite) an ANSWERED outcome BEFORE anything is
            // persisted — the conversation only ever holds the reviewed text.
            AgentRoundAdvisor.Execution execution =
                    () -> round.execute(requestId, sessionId, history, turn.events());
            for (int i = advisors.size() - 1; i >= 0; i--) {
                AgentRoundAdvisor advisor = advisors.get(i);
                AgentRoundAdvisor.Execution inner = execution;
                execution = () -> advisor.aroundRound(context, inner);
            }
            RoundOutcome outcome = execution.proceed();
            total = total.plus(outcome.usage());

            List<TurnMessage> persisted = record(turn, history, costed(outcome));
            for (AgentRoundAdvisor advisor : advisors) {
                try {
                    advisor.afterRoundPersisted(context, persisted);
                } catch (RuntimeException e) {
                    log.warn("After-round advisor failed: {}", e.toString());
                }
            }

            // A round that came back from an aborted call says nothing about
            // the turn: what it had is persisted above, its outcome is not
            // an answer.
            if (isCancelled(turn)) {
                log.debug("turn {} cancelled during a round, after {} model round(s)", requestId, modelRounds);
                return TurnOutcome.cancelled(total, modelRounds);
            }

            switch (outcome.outcome()) {
                case ANSWERED -> {
                    modelRounds++;
                    // What the user said while the model was answering is not
                    // answered yet: the turn goes on instead of ending here.
                    if (!appendSteering(turn, history)) {
                        return TurnOutcome.completed(total, modelRounds, answerText(outcome.added()));
                    }
                }
                case TRUNCATED -> {
                    return TurnOutcome.incomplete(
                            TurnOutcome.IncompleteReason.MAX_OUTPUT_TOKENS, total, modelRounds + 1);
                }
                case CALLS_REQUESTED -> {
                    modelRounds++;
                    if (modelRounds > maxRounds) {
                        log.info("turn {} hit the round cap of {}", requestId, maxRounds);
                        // The calls the model just asked for are persisted and
                        // must not stay open: a tool call without a result is a
                        // history no provider accepts, and the forced answer
                        // that follows would fail on it. Close each with an
                        // error that says why — the model reads it, and the
                        // fold sees a finished call, never one to run later.
                        record(turn, history, refusedResults(history, maxRounds));
                        return TurnOutcome.incomplete(
                                TurnOutcome.IncompleteReason.MAX_ROUNDS, total, modelRounds);
                    }
                }
                case TOOLS_ADVANCED -> { }
                case WAITING_FOR_TOOLS -> {
                    return TurnOutcome.waitingForTools(
                            Set.copyOf(outcome.waitingFor()), total, modelRounds);
                }
            }
        }
    }

    /** Told to stop here, while running — or through the journal, wherever the turn was. */
    private boolean isCancelled(TurnContext turn) {
        return cancelled.contains(turn.requestId()) || turn.journal().cancelRequested();
    }

    /**
     * What the round produced, the first message of a model round carrying
     * what that round cost — one mark per model call, set after the advisors
     * because a reviewer replaces the answer it was given.
     */
    private static List<TurnMessage> costed(RoundOutcome outcome) {
        boolean modelRound = switch (outcome.outcome()) {
            case ANSWERED, TRUNCATED, CALLS_REQUESTED -> true;
            case TOOLS_ADVANCED, WAITING_FOR_TOOLS -> false;
        };
        if (!modelRound || outcome.added().isEmpty()) {
            return outcome.added();
        }
        List<TurnMessage> costed = new ArrayList<>(outcome.added());
        costed.set(0, costed.get(0).withUsage(outcome.usage()));
        return costed;
    }

    /** An error result for every call still open in the episode: the cap refused them. */
    private static List<TurnMessage> refusedResults(List<TurnMessage> history, int maxRounds) {
        List<TurnMessage> results = new ArrayList<>();
        for (ToolCalls.Call call : ToolCalls.of(ToolCalls.episode(history)).open()) {
            results.add(TurnMessage.toolResult(call.callId(), call.name(),
                    "Error: not executed — this turn reached the agent's round cap of "
                            + maxRounds + " tool-call rounds (Max Iterations).", true));
        }
        return results;
    }

    /** Appends what the user said meanwhile; whether there was anything. */
    private boolean appendSteering(TurnContext turn, List<TurnMessage> history) {
        List<TurnMessage> said = turn.steering().drain();
        if (!said.isEmpty()) {
            log.debug("turn {} steered by {} user message(s)", turn.requestId(), said.size());
        }
        record(turn, history, said);
        return !said.isEmpty();
    }

    /**
     * Appends one step's messages as this turn's, then reports them: durable
     * first, visible after, so a watcher never sees something a reload would
     * not show.
     */
    private static List<TurnMessage> record(TurnContext turn, List<TurnMessage> history, List<TurnMessage> added) {
        if (added.isEmpty()) {
            return List.of();
        }
        List<TurnMessage> persisted = turn.journal().append(
                added.stream().map(message -> message.inTurn(turn.requestId())).toList());
        history.addAll(persisted);
        persisted.forEach(stored -> {
            toolEventsOf(stored).forEach(turn.events()::onEvent);
            turn.events().onEvent(new TurnStreamEvent.MessageWritten(stored));
        });
        return persisted;
    }

    /** The tool calls a message asks for, or the result it brings, each as an event of its own. */
    private static List<TurnStreamEvent> toolEventsOf(TurnMessage message) {
        List<TurnStreamEvent> events = new ArrayList<>();
        if (message.type() == TurnMessage.Type.TOOL_CALL) {
            List<TurnMessage.ToolCall> calls = message.toolCalls();
            for (int i = 0; i < calls.size(); i++) {
                TurnMessage.ToolCall call = calls.get(i);
                events.add(new TurnStreamEvent.ToolCallStarted(call.callId(), call.name(), call.arguments(), i));
            }
        } else if (message.type() == TurnMessage.Type.TOOL_RESULT) {
            String callId = String.valueOf(message.metadata().get("callId"));
            String toolName = String.valueOf(message.metadata().getOrDefault("toolName", "tool"));
            events.add(Boolean.TRUE.equals(message.metadata().get("failed"))
                    ? new TurnStreamEvent.ToolCallFailed(callId, toolName, message.text())
                    : new TurnStreamEvent.ToolCallResult(callId, toolName, message.text()));
        }
        return events;
    }

    /** The final answer's text as it went into the store (advisors applied). */
    private static String answerText(List<TurnMessage> added) {
        return added.stream()
                .filter(m -> m.type() == TurnMessage.Type.ASSISTANT)
                .map(TurnMessage::text)
                .reduce((first, second) -> second)
                .orElse("");
    }
}
