package ai.mindconnect.message.adapter.file;

import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.port.out.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import java.nio.file.PathMatcher;

/**
 * Stores messages under:
 *   {base}/conversations/{conversationId}/messages/{seq}_{messageId}.json
 *
 * Co-located with the conversation.json written by FileConversationRepository.
 */
public class FileMessageRepository implements MessageRepository {

    private static final Logger log = Logger.getLogger(FileMessageRepository.class.getName());

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    public FileMessageRepository(Path messageStorageDir, ObjectMapper objectMapper) {
        this.baseDir = messageStorageDir.resolve("conversations").toAbsolutePath();
        this.objectMapper = objectMapper;
        log.info("MessageRepository storage: " + this.baseDir);
    }

    @Override
    public Message save(Message message) {
        try {
            Path dir = messagesDir(message.conversationId());
            Files.createDirectories(dir);
            Path file = fileFor(message);
            boolean isUpdate = Files.exists(file);
            objectMapper.writeValue(file.toFile(), message);
            if (!isUpdate) {
                log.fine("Saved new message " + message.id());
            } else {
                log.fine("Updated existing message " + message.id());
            }
            return message;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<Message> findByConversationId(UUID conversationId, PageRequest page) {
        Path dir = messagesDir(conversationId);
        if (!Files.exists(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .map(p -> {
                        try {
                            return objectMapper.readValue(p.toFile(), Message.class);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .skip(page.offset())
                    .limit(page.size())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Message> findById(UUID conversationId, UUID messageId) {
        Path dir = messagesDir(conversationId);
        if (!Files.exists(dir)) return Optional.empty();
        // Use glob to match "*_{messageId}.json" — avoids full directory scan
        PathMatcher matcher = dir.getFileSystem()
                .getPathMatcher("glob:**/*_" + messageId + ".json");
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(matcher::matches)
                    .findFirst()
                    .map(p -> {
                        try {
                            return objectMapper.readValue(p.toFile(), Message.class);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public int countByConversationId(UUID conversationId) {
        Path dir = messagesDir(conversationId);
        if (!Files.exists(dir)) return 0;
        try (var stream = Files.list(dir)) {
            return (int) stream.filter(p -> p.toString().endsWith(".json")).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void deleteBySequenceRange(UUID conversationId, int fromSeq, int toSeq) {
        Path dir = messagesDir(conversationId);
        if (!Files.exists(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        // filename format: 0000000012_<uuid>.json — extract leading digits
                        String name = p.getFileName().toString();
                        try {
                            int seq = Integer.parseInt(name.substring(0, name.indexOf('_')));
                            if (seq >= fromSeq && seq <= toSeq) {
                                Files.delete(p);
                                log.fine("Deleted message file: " + p.getFileName());
                            }
                        } catch (NumberFormatException ignored) {
                            // skip files that don't match naming convention
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Path messagesDir(UUID conversationId) {
        return baseDir.resolve(conversationId.toString()).resolve("messages");
    }

    private Path fileFor(Message message) {
        String name = String.format("%010d_%s.json", message.sequenceNum(), message.id());
        return messagesDir(message.conversationId()).resolve(name);
    }
}
