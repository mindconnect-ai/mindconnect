package ai.mindconnect.conversation.port.in;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.mindconnect.common.PageRequest;
import ai.mindconnect.conversation.domain.ContentPart;
import ai.mindconnect.conversation.domain.Conversation;
import ai.mindconnect.conversation.domain.ConversationId;
import ai.mindconnect.conversation.domain.Message;
import ai.mindconnect.conversation.domain.Participant;
import ai.mindconnect.conversation.domain.ParticipantId;
import ai.mindconnect.conversation.domain.ParticipantType;

/**
 * Conversations between people and bots, in the namespace the current call
 * works in: opening one, who takes part, what they write.
 */
public interface ConversationManager {

    /** @param channel the channel it happens in; null for none but this installation */
    Conversation open(String topic, String channel, Map<String, Object> metadata);

    Optional<Conversation> find(ConversationId conversation);

    /** Newest first. */
    List<Conversation> list(PageRequest page);

    /** @throws IllegalArgumentException for a conversation that is not there */
    Participant join(ConversationId conversation, ParticipantType type, String refId, String displayName,
                     Map<String, Object> metadata);

    /** The participant takes no further part; what it wrote stays. */
    void leave(ConversationId conversation, ParticipantId participant);

    /**
     * A message written in this installation, handed to the conversation's
     * channel once it is stored.
     *
     * @throws IllegalArgumentException for a conversation that is not there, or a writer that does not
     *                                  take part in it
     */
    Message post(ConversationId conversation, ParticipantId from, List<ContentPart> content,
                 Map<String, Object> metadata);

    /** A message that came in through the conversation's channel: stored, not handed back. */
    Message receive(ConversationId conversation, ParticipantId from, List<ContentPart> content,
                    Map<String, Object> metadata);

    /** Oldest first. */
    List<Message> history(ConversationId conversation, PageRequest page);

    void close(ConversationId conversation);

    /** The conversation, its participants and its messages. */
    void delete(ConversationId conversation);
}
