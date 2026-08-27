package ai.mindconnect.message.service;

import ai.mindconnect.common.DomainException;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.Conversation;
import ai.mindconnect.message.domain.ConversationType;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import ai.mindconnect.message.domain.Participant;
import ai.mindconnect.message.port.in.ConversationManager;
import ai.mindconnect.message.port.out.ConversationRepository;
import ai.mindconnect.message.port.out.MessageRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ConversationService implements ConversationManager {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public ConversationService(ConversationRepository conversationRepository, MessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Override
    public Conversation createConversation(Namespace namespace, String topic,
                                           ConversationType type, List<Participant> participants) {
        Conversation conversation = Conversation.create(namespace, topic, type, participants);
        return conversationRepository.save(conversation);
    }

    @Override
    public ai.mindconnect.message.domain.ConversationHistory loadCompleteHistory(UUID conversationId) {
        return ai.mindconnect.message.domain.ConversationHistory.of(conversationId,
                loadHistory(conversationId, new ai.mindconnect.common.PageRequest(0, Integer.MAX_VALUE)));
    }

    @Override
    public Message addMessageToConversation(UUID conversationId, UUID senderId, ParticipantType senderType,
                                            MessageType type, String content, UUID turnId) {
        return addMessageToConversation(conversationId, senderId, senderType, type, content, turnId,
                null, java.util.Map.of());
    }

    @Override
    public Message addMessageToConversation(UUID conversationId, UUID senderId, ParticipantType senderType,
                                            MessageType type, String content, UUID turnId, Integer run,
                                            java.util.Map<String, Object> metadata) {
        conversationRepository.findById(conversationId)
                .orElseThrow(() -> DomainException.notFound("Conversation", conversationId.toString()));
        int seq = messageRepository.countByConversationId(conversationId) + 1;
        Message message = Message.of(conversationId, senderId, senderType, type, content, seq)
                .withTurnId(turnId)
                .withMetadata(metadata);
        if (run != null) message = message.withRun(run);
        return messageRepository.save(message);
    }

    @Override
    public void compressMessage(UUID conversationId, UUID messageId, String stub, Integer compressedTokenCount) {
        messageRepository.findById(conversationId, messageId)
                .map(m -> m.withCompressed(stub, compressedTokenCount))
                .ifPresent(messageRepository::save);
    }

    @Override
    public void updateTokenCount(UUID conversationId, UUID messageId, int tokenCount) {
        messageRepository.findById(conversationId, messageId)
                .map(m -> m.withTokenCount(tokenCount))
                .ifPresent(messageRepository::save);
    }

    @Override
    public void updateDurationMs(UUID conversationId, UUID messageId, long durationMs) {
        messageRepository.findById(conversationId, messageId)
                .map(m -> m.withDurationMs(durationMs))
                .ifPresent(messageRepository::save);
    }

    @Override
    public Optional<Conversation> findById(UUID conversationId) {
        return conversationRepository.findById(conversationId);
    }

    @Override
    public List<Conversation> listByNamespace(Namespace namespace, PageRequest page) {
        return conversationRepository.findByNamespace(namespace, page);
    }

    @Override
    public List<Message> loadHistory(UUID conversationId, PageRequest page) {
        conversationRepository.findById(conversationId)
                .orElseThrow(() -> DomainException.notFound("Conversation", conversationId.toString()));
        return messageRepository.findByConversationId(conversationId, page);
    }

    @Override
    public int deleteMessages(UUID conversationId, int fromSeq, int toSeq) {
        List<Message> all = messageRepository.findByConversationId(conversationId, new PageRequest(0, 500));
        int count = (int) all.stream()
                .filter(m -> m.sequenceNum() >= fromSeq && m.sequenceNum() <= toSeq)
                .count();
        if (count == 0) return 0;
        messageRepository.deleteBySequenceRange(conversationId, fromSeq, toSeq);
        return count;
    }
}
