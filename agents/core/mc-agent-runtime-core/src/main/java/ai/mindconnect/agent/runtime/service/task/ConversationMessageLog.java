package ai.mindconnect.agent.runtime.service.task;

import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.runtime.port.out.TokenCounter;
import ai.mindconnect.agent.runtime.service.round.MessageLog;
import ai.mindconnect.agent.runtime.service.round.TurnMessage;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.ConversationHistory;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.ParticipantType;
import ai.mindconnect.message.port.in.ConversationManager;

import java.util.List;

/**
 * {@link MessageLog} over the conversation manager — the loop's persistence,
 * a few lines closing over what only this turn knows: who the senders are,
 * which turn stamps the messages, and how tokens are counted.
 *
 * <p>Reads come from the ONE {@link ConversationHistory} the execution
 * loaded (concept 16: read once per execution) — {@link #append} persists
 * AND appends to that instance, so every round's reload-free fold and every
 * window render see the current truth. Constructed without a history (the
 * tool worker only appends), {@link #load} falls back to a fresh load.
 */
public final class ConversationMessageLog implements MessageLog {

    private static final int LOAD_ALL = Integer.MAX_VALUE;

    private final ConversationManager conversationManager;
    /** Nullable — append-only use (tool worker) has no execution-wide history. */
    private final ConversationHistory history;
    private final String userSenderId;
    private final AgentId agentSenderId;
    private final ChatTurnId turnId;
    private final int run;
    private final TokenCounter tokenCounter;

    public ConversationMessageLog(ConversationManager conversationManager,
                                  ConversationHistory history,
                                  String userSenderId, AgentId agentSenderId, ChatTurnId turnId, int run,
                                  TokenCounter tokenCounter) {
        this.conversationManager = conversationManager;
        this.history = history;
        this.userSenderId = userSenderId;
        this.agentSenderId = agentSenderId;
        this.turnId = turnId;
        this.run = run;
        this.tokenCounter = tokenCounter;
    }

    /** Append-only variant — no cached history, {@link #load} reads fresh. */
    public ConversationMessageLog(ConversationManager conversationManager,
                                  String userSenderId, AgentId agentSenderId, ChatTurnId turnId, int run,
                                  TokenCounter tokenCounter) {
        this(conversationManager, null, userSenderId, agentSenderId, turnId, run, tokenCounter);
    }

    @Override
    public List<Message> load(ConversationId conversationId) {
        return history != null
                ? history.messages()
                : conversationManager.loadHistory(conversationId, new PageRequest(0, LOAD_ALL));
    }

    @Override
    public Message append(ConversationId conversationId, TurnMessage turnMessage) {
        String senderId = turnMessage.senderType() == ParticipantType.USER ? userSenderId : agentSenderId.value();
        Message persisted = conversationManager.addMessageToConversation(
                conversationId, senderId, turnMessage.senderType(), turnMessage.type(),
                turnMessage.content(), turnId, run, turnMessage.metadata());
        conversationManager.updateTokenCount(persisted.conversationId(), persisted.id(),
                tokenCounter.countText(turnMessage.content()));
        // Wall-clock durations ride in metadata (the executor measured them);
        // the column is what the UI reads.
        Object durationMs = turnMessage.metadata().get("durationMs");
        if (durationMs instanceof Number duration) {
            conversationManager.updateDurationMs(persisted.conversationId(), persisted.id(), duration.longValue());
        }
        if (history != null) {
            history.append(persisted);
        }
        return persisted;
    }
}
