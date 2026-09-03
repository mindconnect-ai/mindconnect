package ai.mindconnect.agent.starter.file;

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
 * {@code messageStorageDir} ({@code mindconnect.data.base-dir}). Imported by
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
    ConversationRepository conversationRepository(Path messageStorageDir, ObjectMapper objectMapper) {
        return new FileConversationRepository(messageStorageDir, objectMapper);
    }

    @Bean
    MessageRepository messageRepository(Path messageStorageDir, ObjectMapper objectMapper) {
        return new FileMessageRepository(messageStorageDir, objectMapper);
    }
}
