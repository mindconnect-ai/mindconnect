package ai.mindconnect.conversation.adapter.file;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.conversation.domain.ConversationId;
import ai.mindconnect.conversation.domain.Message;
import ai.mindconnect.conversation.domain.MessageId;
import ai.mindconnect.conversation.port.out.MessageRepository;
import ai.mindconnect.filerepo.FileRepo;
import ai.mindconnect.filerepo.RecordLog;

/**
 * Messages under {@code {base}/{namespace}/conversation-threads/{id}/messages/},
 * one file per message named by its place and id. The record log hands out
 * the next place under the conversation's lock, so two appends never share
 * one, and a place is not given again — also after a restart.
 */
public final class FileMessageRepository implements MessageRepository {

    private final RecordLog<ConversationId, Message> messages;

    public FileMessageRepository(Path base, ObjectMapper json, ScopeSupplier scope) {
        this.messages = RecordLog.of(Message.class)
                .dir((ConversationId conversation) ->
                        FileConversationRepository.THREADS + "/" + conversation.value() + "/messages")
                .key(Message::sequence)
                .id(message -> message.id().value())
                .build(() -> FileRepo.open(base, scope.namespace().value()), json);
    }

    @Override
    public Message append(ConversationId conversation, LongFunction<Message> create) {
        return messages.append(conversation, create);
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
    public void deleteByConversation(ConversationId conversation) {
        messages.deleteAll(conversation);
    }
}
