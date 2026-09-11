package ai.mindconnect.message.service;

import ai.mindconnect.common.DomainException;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.message.domain.ContentPart;
import ai.mindconnect.message.domain.Conversation;
import ai.mindconnect.message.domain.ConversationHistory;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.ConversationType;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageId;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.Participant;
import ai.mindconnect.message.domain.ParticipantType;
import ai.mindconnect.message.port.in.ConversationManager;
import ai.mindconnect.message.port.out.ConversationRepository;
import ai.mindconnect.message.port.out.MessageRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;

public class ConversationService implements ConversationManager {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public ConversationService(ConversationRepository conversationRepository, MessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Override
    public Conversation createConversation(ConversationId id, String topic,
                                           ConversationType type, List<Participant> participants) {
        Conversation conversation = Conversation.create(id, topic, type, participants);
        return conversationRepository.save(conversation);
    }

    @Override
    public ConversationHistory loadCompleteHistory(ConversationId conversation) {
        return ConversationHistory.of(conversation,
                loadHistory(conversation, new PageRequest(0, Integer.MAX_VALUE)));
    }

    @Override
    public Message addMessageToConversation(ConversationId conversation, String senderId, ParticipantType senderType,
                                            MessageType type, String content, ChatTurnId turnId) {
        return addMessageToConversation(conversation, senderId, senderType, type, content, turnId,
                null, Map.of());
    }

    @Override
    public Message addMessageToConversation(ConversationId conversation, String senderId, ParticipantType senderType,
                                            MessageType type, String content, ChatTurnId turnId, Integer run,
                                            Map<String, Object> metadata) {
        return append(conversation, seq -> Message.of(conversation, senderId, senderType, type, content, seq),
                turnId, run, metadata);
    }

    @Override
    public Message addMessageToConversation(ConversationId conversation, String senderId, ParticipantType senderType,
                                            MessageType type, List<ContentPart> parts, ChatTurnId turnId, Integer run,
                                            Map<String, Object> metadata) {
        return append(conversation, seq -> Message.of(conversation, senderId, senderType, type, parts, seq),
                turnId, run, metadata);
    }

    /**
     * The shared tail: the conversation must exist, and the store hands out
     * the sequence number as it writes. Counting the messages here and
     * writing afterwards is what used to give two messages the same number
     * whenever a turn appended twice at once.
     */
    private Message append(ConversationId conversation, IntFunction<Message> create,
                           ChatTurnId turnId, Integer run, Map<String, Object> metadata) {
        requireConversation(conversation);
        return messageRepository.append(conversation, seq -> {
            Message message = create.apply(seq).withTurnId(turnId).withMetadata(metadata);
            return run != null ? message.withRun(run) : message;
        });
    }

    @Override
    public void compressMessage(ConversationId conversation, MessageId message, String stub, Integer compressedTokenCount) {
        messageRepository.update(conversation, message, m -> m.withCompressed(stub, compressedTokenCount));
    }

    @Override
    public void updateTokenCount(ConversationId conversation, MessageId message, int tokenCount) {
        messageRepository.update(conversation, message, m -> m.withTokenCount(tokenCount));
    }

    @Override
    public void updateDurationMs(ConversationId conversation, MessageId message, long durationMs) {
        messageRepository.update(conversation, message, m -> m.withDurationMs(durationMs));
    }

    @Override
    public Optional<Conversation> findById(ConversationId id) {
        return conversationRepository.findById(id);
    }

    @Override
    public List<Conversation> list(PageRequest page) {
        return conversationRepository.findAll(page);
    }

    @Override
    public List<Message> loadHistory(ConversationId conversation, PageRequest page) {
        requireConversation(conversation);
        return messageRepository.findByConversation(conversation, page);
    }

    @Override
    public int deleteMessages(ConversationId conversation, int fromSeq, int toSeq) {
        List<Message> all = messageRepository.findByConversation(conversation, new PageRequest(0, 500));
        int count = (int) all.stream()
                .filter(m -> m.sequenceNum() >= fromSeq && m.sequenceNum() <= toSeq)
                .count();
        if (count == 0) return 0;
        messageRepository.deleteBySequenceRange(conversation, fromSeq, toSeq);
        return count;
    }

    private void requireConversation(ConversationId conversation) {
        conversationRepository.findById(conversation)
                .orElseThrow(() -> DomainException.notFound("Conversation", conversation.toString()));
    }
}
