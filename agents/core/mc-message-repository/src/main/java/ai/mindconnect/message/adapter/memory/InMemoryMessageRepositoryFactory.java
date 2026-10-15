package ai.mindconnect.message.adapter.memory;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.message.port.out.ConversationRepository;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.message.port.out.MessageRepositoryFactory;

/** Conversations and messages in memory, one store per namespace — nothing survives the process. */
public class InMemoryMessageRepositoryFactory implements MessageRepositoryFactory {

    private final ScopeSupplier scope;

    public InMemoryMessageRepositoryFactory(ScopeSupplier scope) {
        this.scope = scope;
    }

    @Override
    public ConversationRepository conversationRepository() {
        return new InMemoryConversationRepository(scope);
    }

    @Override
    public MessageRepository messageRepository() {
        return new InMemoryMessageRepository(scope);
    }
}
