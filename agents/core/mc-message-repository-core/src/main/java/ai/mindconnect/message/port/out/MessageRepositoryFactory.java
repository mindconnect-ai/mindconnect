package ai.mindconnect.message.port.out;

/**
 * Creates the conversation and message repositories for one persistence
 * backend. One implementation per backend — file, in-memory, Postgres — so
 * that whoever assembles a runtime picks a factory once instead of switching
 * per repository.
 */
public interface MessageRepositoryFactory {

    ConversationRepository conversationRepository();

    MessageRepository messageRepository();
}
