package ai.mindconnect.message.adapter.memory;

import ai.mindconnect.message.port.out.ConversationRepository;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.message.port.out.MessageRepositoryFactory;

/** Conversations and messages in memory — nothing survives the process. */
public class InMemoryMessageRepositoryFactory implements MessageRepositoryFactory {

    @Override
    public ConversationRepository conversationRepository() {
        return new InMemoryConversationRepository();
    }

    @Override
    public MessageRepository messageRepository() {
        return new InMemoryMessageRepository();
    }
}
