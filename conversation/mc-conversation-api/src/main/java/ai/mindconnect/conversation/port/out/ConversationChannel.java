package ai.mindconnect.conversation.port.out;

import ai.mindconnect.conversation.domain.Conversation;
import ai.mindconnect.conversation.domain.Message;

/**
 * Where conversations happen besides this installation: WhatsApp, Teams.
 * A channel hands what comes in to {@code ConversationManager.receive} and
 * gets what is written here through {@link #deliver}.
 */
public interface ConversationChannel {

    /** The name a conversation names its channel by, such as {@code whatsapp}. */
    String name();

    /**
     * Sends a message written in this installation to the participants the
     * channel reaches. A message that came in through the channel is not
     * handed back to it.
     */
    void deliver(Conversation conversation, Message message);
}
