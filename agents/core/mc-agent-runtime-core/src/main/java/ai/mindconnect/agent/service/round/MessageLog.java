package ai.mindconnect.agent.service.round;

import ai.mindconnect.message.domain.Message;

import java.util.List;
import java.util.UUID;

/**
 * The loop's two hands on the conversation: load the truth, append what a
 * round produced. Deliberately this narrow — the production adapter is a few
 * lines over ConversationManager (closing over sender ids and turnId), and a
 * test is a list.
 *
 * <p>v1 loads the COMPLETE conversation. Loading less is a cost optimisation
 * with one safety rule (concept 16): the cut must never split an open call
 * pair — cutting at turn boundaries is safe, since turn boundaries close
 * calls.
 */
public interface MessageLog {

    List<Message> load(UUID conversationId);

    Message append(UUID conversationId, TurnMessage message);
}
