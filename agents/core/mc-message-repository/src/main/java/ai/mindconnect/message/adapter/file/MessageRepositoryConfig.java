package ai.mindconnect.message.adapter.file;

import ai.mindconnect.message.port.out.ConversationRepository;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.message.service.ConversationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
@ComponentScan(basePackageClasses = MessageRepositoryConfig.class)
public class MessageRepositoryConfig {

    @Bean
    Path messageStorageDir(@Value("${mindconnect.data.base-dir:data}") String dir) {
        return Path.of(dir);
    }

    @Bean
    ConversationService conversationService(ConversationRepository conversationRepository,
                                            MessageRepository messageRepository) {
        return new ConversationService(conversationRepository, messageRepository);
    }
}
