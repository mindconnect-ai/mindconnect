package ai.mindconnect.message.port.out;

import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.Message;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository {

    /**
     * Persists a message. If a message with the same {@code id} already exists
     * it is overwritten (upsert) — this allows updating the compressed flag.
     */
    Message save(Message message);

    List<Message> findByConversationId(UUID conversationId, PageRequest page);

    Optional<Message> findById(UUID conversationId, UUID messageId);

    int countByConversationId(UUID conversationId);

    /** Deletes all messages whose sequenceNum is in [fromSeq, toSeq] inclusive. */
    void deleteBySequenceRange(UUID conversationId, int fromSeq, int toSeq);
}
