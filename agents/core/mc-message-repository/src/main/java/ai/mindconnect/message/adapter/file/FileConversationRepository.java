package ai.mindconnect.message.adapter.file;

import ai.mindconnect.common.util.AtomicFiles;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.message.domain.ConversationId;

import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.Conversation;
import ai.mindconnect.message.port.out.ConversationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.logging.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Stores conversations under:
 *   {base}/conversations/{conversationId}/conversation.json
 *
 * Co-locating with messages (stored by FileMessageRepository in the same dir)
 * makes it easy to find everything for a conversation in one folder.
 */
public class FileConversationRepository implements ConversationRepository {

    private static final Logger log = Logger.getLogger(FileConversationRepository.class.getName());
    static final String FILE_NAME = "conversation.json";

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    public FileConversationRepository(Path messageStorageDir, ObjectMapper objectMapper, Namespace namespace) {
        this.baseDir = messageStorageDir.resolve(namespace.value()).resolve("conversations").toAbsolutePath();
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
            AtomicFiles.write(file, out -> objectMapper.writeValue(out, conversation));
            return conversation;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Conversation> findById(ConversationId id) {
        Path file = fileFor(id);
        if (!Files.exists(file)) return Optional.empty();
        Conversation conversation = read(file);
        // The directory is flat: a file of another tenant with the same value is not this conversation.
        return conversation.id().equals(id) ? Optional.of(conversation) : Optional.empty();
    }

    /** A conversation written before the namespace was recorded takes the one asked for. */
    private Conversation read(Path file) {
        try {
            return objectMapper.readerFor(Conversation.class)
                    .readValue(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<Conversation> findAll(PageRequest page) {
        if (!Files.exists(baseDir)) return List.of();
        try (var stream = Files.list(baseDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(d -> d.resolve(FILE_NAME))
                    .filter(Files::exists)
                    .map(f -> read(f))
                    .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                    .skip(page.offset())
                    .limit(page.size())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path fileFor(ConversationId id) {
        return baseDir.resolve(id.value()).resolve(FILE_NAME);
    }
}
