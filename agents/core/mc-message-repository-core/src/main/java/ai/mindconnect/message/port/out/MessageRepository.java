package ai.mindconnect.message.port.out;

import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageId;

import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;

/**
 * Storage of messages. A message is a child of its conversation, so it is
 * addressed by the conversation and its own id — the adapter knows which
 * conversation to open without searching them all.
 */
public interface MessageRepository {

    /**
     * Stores a message with the sequence number it carries, replacing a message
     * of the same {@code id} — for importing and for tests. A new message of a
     * running conversation goes through {@link #append}, a change to a stored
     * one through {@link #update}.
     */
    Message save(Message message);

    /**
     * Applies {@code change} to the stored message and stores the result, as one
     * step: the turn setting a token count and a memory strategy compressing the
     * same message never overwrite each other. {@code change} runs while the store
     * holds the message and may run more than once — build the changed message,
     * nothing else; it must keep the id and the sequence number.
     *
     * @return the message as stored afterwards, or empty when there is none
     */
    Optional<Message> update(ConversationId conversation, MessageId id, UnaryOperator<Message> change);

    List<Message> findByConversation(ConversationId conversation, PageRequest page);

    Optional<Message> findById(ConversationId conversation, MessageId id);

    /**
     * Appends a message with the next free sequence number of its
     * conversation, and gives back what was stored.
     *
     * <p>The number is the adapter's to hand out, because only the adapter
     * knows how to reserve one without a second writer getting the same:
     * the store-backed ones take a lock per conversation, the SQL one lets
     * the database count. Reading the highest number and writing a row are
     * one step here; apart, two turns appending at the same moment (two
     * tool results arriving together, say) both read the same number and
     * the conversation ends up with two messages claiming it.
     *
     * <p>{@code create} is handed the reserved number and returns the
     * message to store. It is called while the reservation is held, so it
     * should do nothing but build the message.
     */
    Message append(ConversationId conversation, IntFunction<Message> create);

    /** Deletes all messages whose sequenceNum is in [fromSeq, toSeq] inclusive. */
    void deleteBySequenceRange(ConversationId conversation, int fromSeq, int toSeq);
}
