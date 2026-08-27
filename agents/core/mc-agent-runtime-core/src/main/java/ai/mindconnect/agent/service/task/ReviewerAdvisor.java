package ai.mindconnect.agent.service.task;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.agent.port.in.AgentTaskRunner;
import ai.mindconnect.agent.service.round.AgentRoundAdvisor;
import ai.mindconnect.agent.service.round.RoundOutcome;
import ai.mindconnect.agent.service.round.TurnMessage;
import ai.mindconnect.agent.service.turn.ResponseReviewerChain;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import ai.mindconnect.message.port.in.ConversationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The response-reviewer chain as an {@link AgentRoundAdvisor}: it wraps the
 * round, and when the round ANSWERED it rewrites the answer text — before the
 * loop persists it, so the conversation only ever holds the reviewed text and
 * a turn still produces exactly one agent CHAT message.
 *
 * <p>Every other outcome passes through untouched; the reviewers have nothing
 * to say about tool rounds.
 */
public final class ReviewerAdvisor implements AgentRoundAdvisor {

    private final AgentTaskRunner agentTaskRunner;
    private final ConversationManager conversationManager;
    private final AgentDefinition def;
    private final AgentSession session;
    /** The turn's user message — what the reviewers judge the answer against. Nullable. */
    private final Message userMessage;
    private final Consumer<StreamEvent> stream;

    public ReviewerAdvisor(AgentTaskRunner agentTaskRunner, ConversationManager conversationManager,
                           AgentDefinition def, AgentSession session,
                           Message userMessage, Consumer<StreamEvent> stream) {
        this.agentTaskRunner = agentTaskRunner;
        this.conversationManager = conversationManager;
        this.def = def;
        this.session = session;
        this.userMessage = userMessage;
        this.stream = stream;
    }

    @Override
    public RoundOutcome aroundRound(RoundContext context, Execution execution) {
        RoundOutcome outcome = execution.proceed();
        if (outcome.outcome() != RoundOutcome.Outcome.ANSWERED) return outcome;

        List<TurnMessage> reviewed = new ArrayList<>(outcome.added().size());
        for (TurnMessage message : outcome.added()) {
            boolean finalAnswer = message.type() == MessageType.CHAT
                    && message.senderType() == ParticipantType.AGENT;
            reviewed.add(finalAnswer
                    ? new TurnMessage(message.type(), message.senderType(),
                            review(message.content()), message.metadata())
                    : message);
        }
        return new RoundOutcome(reviewed, outcome.outcome(), outcome.usage(), outcome.waitingFor());
    }

    /**
     * Runs the chain over a draft. Public because the worker's forced final
     * answer (MAX_ROUNDS) bypasses the loop and must still be reviewed.
     */
    public String review(String draft) {
        if (userMessage == null) return draft;
        return new ResponseReviewerChain(agentTaskRunner, conversationManager, def,
                userMessage.content(), draft, session.conversationId(), userMessage.id(), stream)
                .run();
    }
}
