package ai.mindconnect.message.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.filerepo.FileRepo;
import ai.mindconnect.filerepo.RecordLog;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageId;
import ai.mindconnect.message.port.out.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;

/**
 * Stores messages under:
 *   {base}/{namespace}/conversations/{conversationId}/messages/{seq}_{messageId}.json
 *
 * <p>Co-located with the conversation.json written by FileConversationRepository.
 * The directory is a {@link RecordLog}: the next sequence number comes from its
 * in-memory index under the conversation's write lock, so appending lists
 * nothing and two appends never share a number — not even after the newest
 * messages were deleted. A history page reads only its own messages, and
 * finding a message by id opens one file.
 */
public class FileMessageRepository implements MessageRepository {

    private static final Logger log = Logger.getLogger(FileMessageRepository.class.getName());

    private final RecordLog<ConversationId, Message> messages;

    public FileMessageRepository(Path messageStorageDir, ObjectMapper objectMapper, Namespace namespace) {
        FileRepo repo = FileRepo.open(messageStorageDir, namespace.value());
        this.messages = RecordLog.of(Message.class)
                .dir((ConversationId conversation) -> "conversations/" + conversation.value() + "/messages")
                .key(Message::sequenceNum)
                .id(m -> m.id().value())
                .build(repo, objectMapper);
        log.info("MessageRepository storage: " + repo.resolve("conversations"));
    }

    @Override
    public Message save(Message message) {
        return messages.put(message.conversationId(), message);
    }

    @Override
    public Optional<Message> update(ConversationId conversation, MessageId id, UnaryOperator<Message> change) {
        return messages.update(conversation, id.value(), change);
    }

    @Override
    public List<Message> findByConversation(ConversationId conversation, PageRequest page) {
        return messages.page(conversation, page.offset(), page.size());
    }

    @Override
    public Optional<Message> findById(ConversationId conversation, MessageId id) {
        return messages.find(conversation, id.value());
    }

    @Override
    public Message append(ConversationId conversation, IntFunction<Message> create) {
        return messages.append(conversation, seq -> create.apply(Math.toIntExact(seq)));
    }

    @Override
    public void deleteBySequenceRange(ConversationId conversation, int fromSeq, int toSeq) {
        messages.deleteKeyRange(conversation, fromSeq, toSeq);
    }
}
