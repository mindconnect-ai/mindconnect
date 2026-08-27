package ai.mindconnect.message.adapter.file;

import ai.mindconnect.common.Namespace;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.Conversation;
import ai.mindconnect.message.port.out.ConversationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.logging.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores conversations under:
 *   {base}/conversations/{conversationId}/conversation.json
 *
 * Co-locating with messages (stored by FileMessageRepository in the same dir)
 * makes it easy to find everything for a conversation in one folder.
 */
@Component
public class FileConversationRepository implements ConversationRepository {

    private static final Logger log = Logger.getLogger(FileConversationRepository.class.getName());
    static final String FILE_NAME = "conversation.json";

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    public FileConversationRepository(Path messageStorageDir, ObjectMapper objectMapper) {
        this.baseDir = messageStorageDir.resolve("conversations").toAbsolutePath();
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        log.info("ConversationRepository storage: " + this.baseDir);
    }

    @Override
    public Conversation save(Conversation conversation) {
        Path file = fileFor(conversation.id());
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writeValue(file.toFile(), conversation);
            return conversation;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Conversation> findById(UUID id) {
        Path file = fileFor(id);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), Conversation.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<Conversation> findByNamespace(Namespace namespace, PageRequest page) {
        if (!Files.exists(baseDir)) return List.of();
        try (var stream = Files.list(baseDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(d -> d.resolve(FILE_NAME))
                    .filter(Files::exists)
                    .map(f -> {
                        try {
                            return objectMapper.readValue(f.toFile(), Conversation.class);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .filter(c -> c.namespace().equals(namespace))
                    .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                    .skip(page.offset())
                    .limit(page.size())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path fileFor(UUID id) {
        return baseDir.resolve(id.toString()).resolve(FILE_NAME);
    }
}
