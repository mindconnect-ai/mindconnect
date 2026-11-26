package ai.mindconnect.conversation.adapter.file;

import java.nio.file.Path;

import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.mindconnect.conversation.port.out.ConversationRepository;
import ai.mindconnect.conversation.port.out.ConversationStoreContract;
import ai.mindconnect.conversation.port.out.MessageRepository;
import ai.mindconnect.scope.TenantScope;

/** The store on files keeps what every store keeps. */
class FileConversationStoreTest extends ConversationStoreContract {

    private static final TenantScope SCOPE = TenantScope.fixed("test");

    @TempDir
    Path directory;

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @Override
    protected ConversationRepository conversations() {
        return new FileConversationRepository(directory, json, SCOPE);
    }

    @Override
    protected MessageRepository messages() {
        return new FileMessageRepository(directory, json, SCOPE);
    }
}
