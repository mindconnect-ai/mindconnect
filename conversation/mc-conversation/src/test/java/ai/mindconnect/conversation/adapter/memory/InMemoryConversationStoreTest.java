package ai.mindconnect.conversation.adapter.memory;

import ai.mindconnect.conversation.port.out.ConversationRepository;
import ai.mindconnect.conversation.port.out.ConversationStoreContract;
import ai.mindconnect.conversation.port.out.MessageRepository;
import ai.mindconnect.scope.TenantScope;

/** The store in memory keeps what every store keeps. */
class InMemoryConversationStoreTest extends ConversationStoreContract {

    private static final TenantScope SCOPE = TenantScope.fixed("test");

    private final ConversationRepository conversations = new InMemoryConversationRepository(SCOPE);
    private final MessageRepository messages = new InMemoryMessageRepository(SCOPE);

    @Override
    protected ConversationRepository conversations() {
        return conversations;
    }

    @Override
    protected MessageRepository messages() {
        return messages;
    }
}
