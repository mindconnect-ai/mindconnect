package ai.mindconnect.message.adapter.file;

import ai.mindconnect.message.port.out.ConversationRepository;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.message.service.ConversationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class MessageRepositoryConfig {

    @Bean
    ConversationService conversationService(ConversationRepository conversationRepository,
                                            MessageRepository messageRepository) {
        return new ConversationService(conversationRepository, messageRepository);
    }
}
