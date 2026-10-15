package ai.mindconnect.message.adapter.memory;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.message.port.in.ConversationManager;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.message.service.ConversationService;

/**
 * Plain-Java (no-Spring) composition of the in-memory message layer — the in-memory counterpart to
 * {@code FileMessageStore}. Holds a conversation + message repository and the {@link ConversationManager}
 * over them.
 */
public final class InMemoryMessageStore {

    private final MessageRepository messageRepository;
    private final ConversationManager conversationManager;

    public InMemoryMessageStore(ScopeSupplier scope) {
        this.messageRepository = new InMemoryMessageRepository(scope);
        this.conversationManager = new ConversationService(new InMemoryConversationRepository(scope), messageRepository);
    }

    public MessageRepository messageRepository() {
        return messageRepository;
    }

    public ConversationManager conversationManager() {
        return conversationManager;
    }
}
