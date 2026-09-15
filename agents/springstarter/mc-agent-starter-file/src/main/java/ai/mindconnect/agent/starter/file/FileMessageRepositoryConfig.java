package ai.mindconnect.agent.starter.file;

import ai.mindconnect.agent.NamespaceRouted;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.message.adapter.file.FileConversationRepository;
import ai.mindconnect.message.adapter.file.FileMessageRepository;
import ai.mindconnect.message.port.out.ConversationRepository;
import ai.mindconnect.message.port.out.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Conversations and messages on the file system under
 * {@code messageStorageDir/<namespace>} ({@code mindconnect.data.base-dir}),
 * routed per namespace like every other store. Imported by
 * {@link FilePersistenceAutoConfiguration} when {@code mindconnect.persistence}
 * is {@code file}.
 */
@Configuration
public class FileMessageRepositoryConfig {

    @Bean
    Path messageStorageDir(@Value("${mindconnect.data.base-dir:data}") String dir) {
        return Path.of(dir);
    }

    @Bean
    ConversationRepository conversationRepository(Path messageStorageDir, ObjectMapper objectMapper, ScopeSupplier scope) {
        return NamespaceRouted.route(ConversationRepository.class, scope,
                ns -> new FileConversationRepository(messageStorageDir, objectMapper, ns));
    }

    @Bean
    MessageRepository messageRepository(Path messageStorageDir, ObjectMapper objectMapper, ScopeSupplier scope) {
        return NamespaceRouted.route(MessageRepository.class, scope,
                ns -> new FileMessageRepository(messageStorageDir, objectMapper, ns));
    }
}
