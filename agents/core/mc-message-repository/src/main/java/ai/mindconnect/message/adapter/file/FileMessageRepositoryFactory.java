package ai.mindconnect.message.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.message.port.out.ConversationRepository;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.message.port.out.MessageRepositoryFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;

/** Conversations and messages as files under {@code <baseDir>/<namespace>/conversations}. */
public class FileMessageRepositoryFactory implements MessageRepositoryFactory {

    private final Path baseDir;
    private final ObjectMapper objectMapper;
    private final Namespace namespace;

    public FileMessageRepositoryFactory(Path baseDir, ObjectMapper objectMapper, Namespace namespace) {
        this.baseDir = baseDir;
        this.objectMapper = objectMapper;
        this.namespace = namespace;
    }

    @Override
    public ConversationRepository conversationRepository() {
        return new FileConversationRepository(baseDir, objectMapper, namespace);
    }

    @Override
    public MessageRepository messageRepository() {
        return new FileMessageRepository(baseDir, objectMapper, namespace);
    }
}
