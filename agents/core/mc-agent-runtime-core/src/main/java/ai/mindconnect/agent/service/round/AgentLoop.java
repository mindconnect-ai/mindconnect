package ai.mindconnect.agent.service.round;

import ai.mindconnect.common.Cancellation;
import ai.mindconnect.message.domain.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The agent loop: turns {@link AgentRound} round after round until the model
 * has answered or something is missing. What comes out is a turn — one unit of
 * conversation, one {@link TurnOutcome}.
 *
 * <p>Everything that happens BETWEEN two rounds happens here, and only here:
 * persist, publish, check the cancel, count the round brake. The round knows
 * nothing of it — which limits the round to its actual topic: what to do in
 * this one revolution.
 *
 * <p>The loop is ordinary code, not an apparatus. Whoever wants more —
 * compaction, summaries, reviewers — writes it into this loop or around it,
 * instead of slipping it under the round as an advisor.
 *
 * <p>This class knows no task queue. Running it in the background is the
 * worker's business (concept 16, step 4).
 */
public final class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final AgentRound round;
    private final MessageLog messages;
    private final int maxRounds;
    private final Consumer<Message> published;
    private final List<AgentRoundAdvisor> advisors;

    /**
     * @param published where a PERSISTED message is announced live — durable
     *                  first, visible after, so a client never sees something
     *                  a reload would not show. {@code m -> { }} when nobody
     *                  watches.
     */
    public AgentLoop(AgentRound round, MessageLog messages, int maxRounds,
                     Consumer<Message> published) {
        this(round, messages, maxRounds, published, List.of());
    }

    /**
     * @param advisors turn-level policy around each round (reviewer chain,
     *                 tool-result compression) — in list order, first advisor
     *                 outermost. See {@link AgentRoundAdvisor}.
     */
    public AgentLoop(AgentRound round, MessageLog messages, int maxRounds,
                     Consumer<Message> published, List<AgentRoundAdvisor> advisors) {
        this.round = round;
        this.messages = messages;
        this.maxRounds = maxRounds;
        this.published = published;
        this.advisors = List.copyOf(advisors);
    }

    /**
     * Turns the turn to its end — or to its next wait.
     *
     * <p>The user's input is NOT passed in: it is already in the conversation,
     * appended by the API layer before the turn started. That is why a second
     * run is harmless — a turn resumed after an approval does not append the
     * question again.
     *
     * <p>Returns even when the turn is NOT finished: waiting only for a tool
     * is a {@link TurnOutcome#waitingForTools} and the caller resumes once
     * those calls are done. Waiting here would hold a thread and all state for
     * an unknown time — and not survive a restart.
     *
     * @param cancellation the same handle the {@link LlmProvider} gets: the
     *                     loop polls it between rounds, the gateway hangs its
     *                     abort hook on it. One concept, one object.
     * @param roundsSoFar  the model rounds of earlier attempts. Without it the
     *                     round brake restarts after every wait — the history
     *                     is in the conversation, the count is not.
     */
    public TurnOutcome run(String requestId, UUID conversationId, UUID sessionId,
                           Cancellation cancellation, int roundsSoFar) {
        List<Message> history = new ArrayList<>(messages.load(conversationId));

        Usage total = Usage.ZERO;
        int modelRounds = roundsSoFar;

        while (true) {
            if (cancellation.isCancelled()) {
                log.debug("turn {} cancelled after {} model round(s)", requestId, modelRounds);
                return TurnOutcome.cancelled(total, modelRounds);
            }

            AgentRoundAdvisor.RoundContext context = new AgentRoundAdvisor.RoundContext(
                    requestId, conversationId, sessionId, List.copyOf(history));
            // Advisors wrap the round like an onion: the reviewer chain sees
            // (and may rewrite) an ANSWERED outcome BEFORE anything is
            // persisted — the conversation only ever holds the reviewed text.
            AgentRoundAdvisor.Execution execution =
                    () -> round.execute(requestId, sessionId, history, cancellation);
            for (int i = advisors.size() - 1; i >= 0; i--) {
                AgentRoundAdvisor advisor = advisors.get(i);
                AgentRoundAdvisor.Execution inner = execution;
                execution = () -> advisor.aroundRound(context, inner);
            }
            RoundOutcome outcome = execution.proceed();
            total = total.plus(outcome.usage());

            List<Message> persisted = record(conversationId, history, outcome.added());
            for (AgentRoundAdvisor advisor : advisors) {
                try {
                    advisor.afterRoundPersisted(context, persisted);
                } catch (RuntimeException e) {
                    log.warn("After-round advisor failed: {}", e.toString());
                }
            }

            switch (outcome.outcome()) {
                case ANSWERED -> {
                    return TurnOutcome.completed(total, modelRounds + 1, answerText(outcome.added()));
                }
                case TRUNCATED -> {
                    return TurnOutcome.incomplete(
                            TurnOutcome.IncompleteReason.MAX_OUTPUT_TOKENS, total, modelRounds + 1);
                }
                case CALLS_REQUESTED -> {
                    if (++modelRounds >= maxRounds) {
                        log.info("turn {} hit the round cap of {}", requestId, maxRounds);
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

    /** Append, then announce. A failing announcer does not cost the round. */
    private List<Message> record(UUID conversationId, List<Message> history, List<TurnMessage> added) {
        List<Message> persisted = new ArrayList<>(added.size());
        for (TurnMessage turnMessage : added) {
            Message stored = messages.append(conversationId, turnMessage);
            history.add(stored);
            persisted.add(stored);
            try {
                published.accept(stored);
            } catch (RuntimeException e) {
                log.warn("Publishing message {} failed: {}", stored.id(), e.toString());
            }
        }
        return persisted;
    }

    /** The final answer's text as it went into the store (advisors applied). */
    private static String answerText(List<TurnMessage> added) {
        return added.stream()
                .filter(m -> m.type() == ai.mindconnect.message.domain.MessageType.CHAT
                        && m.senderType() == ai.mindconnect.message.domain.ParticipantType.AGENT)
                .map(TurnMessage::content)
                .reduce((first, second) -> second)
                .orElse("");
    }
}
