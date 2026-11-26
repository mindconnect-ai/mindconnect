package ai.mindconnect.conversation.port.out;

import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;

import ai.mindconnect.common.PageRequest;
import ai.mindconnect.conversation.domain.ConversationId;
import ai.mindconnect.conversation.domain.Message;
import ai.mindconnect.conversation.domain.MessageId;

/** The messages of the conversations of the namespace the current call works in, each conversation's in order. */
public interface MessageRepository {

    /**
     * Stores the message {@code create} builds for the next place in the
     * conversation. Two appends never get the same place, and a place is not
     * given again after the messages at the end were deleted.
     */
    Message append(ConversationId conversation, LongFunction<Message> create);

    /** Replaces one message by what {@code change} makes of it — its metadata, as a rule. */
    Optional<Message> update(ConversationId conversation, MessageId id, UnaryOperator<Message> change);

    /** Oldest first. */
    List<Message> findByConversation(ConversationId conversation, PageRequest page);

    Optional<Message> findById(ConversationId conversation, MessageId id);

    void deleteByConversation(ConversationId conversation);
}
