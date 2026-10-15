package ai.mindconnect.message.adapter.file;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.message.port.out.ConversationRepository;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.message.port.out.MessageRepositoryFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;

/** Conversations and messages as files under {@code <baseDir>/<namespace>/conversations}, the namespace the scope names per call. */
public class FileMessageRepositoryFactory implements MessageRepositoryFactory {

    private final Path baseDir;
    private final ObjectMapper objectMapper;
    private final ScopeSupplier scope;

    public FileMessageRepositoryFactory(Path baseDir, ObjectMapper objectMapper, ScopeSupplier scope) {
        this.baseDir = baseDir;
        this.objectMapper = objectMapper;
        this.scope = scope;
    }

    @Override
    public ConversationRepository conversationRepository() {
        return new FileConversationRepository(baseDir, objectMapper, scope);
    }

    @Override
    public MessageRepository messageRepository() {
        return new FileMessageRepository(baseDir, objectMapper, scope);
    }
}
