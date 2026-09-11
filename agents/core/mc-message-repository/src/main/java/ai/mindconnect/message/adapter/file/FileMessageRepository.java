package ai.mindconnect.message.adapter.file;

import ai.mindconnect.common.util.AtomicFiles;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.MessageId;

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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntFunction;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.nio.file.PathMatcher;

/**
 * Stores messages under:
 *   {base}/conversations/{conversationId}/messages/{seq}_{messageId}.json
 *
 * Co-located with the conversation.json written by FileConversationRepository.
 */
public class FileMessageRepository implements MessageRepository {

    private static final Logger log = Logger.getLogger(FileMessageRepository.class.getName());

    /**
     * Locks for {@link #append}, one bucket per conversation by hash. A
     * fixed set rather than one per conversation: nothing to create, and
     * nothing that grows for as long as the process lives. Two
     * conversations sharing a bucket wait for each other now and then,
     * which costs a moment and breaks nothing.
     *
     * <p>Process-wide, which is what a directory of files is: two JVMs
     * writing into the same store would still hand out the same number.
     */
    private static final int APPEND_LOCKS = 64;

    private final ReentrantLock[] appendLocks = Stream.generate(ReentrantLock::new)
            .limit(APPEND_LOCKS).toArray(ReentrantLock[]::new);

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    public FileMessageRepository(Path messageStorageDir, ObjectMapper objectMapper, Namespace namespace) {
        this.baseDir = messageStorageDir.resolve(namespace.value()).resolve("conversations").toAbsolutePath();
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
            AtomicFiles.write(file, out -> objectMapper.writeValue(out, message));
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
    public List<Message> findByConversation(ConversationId conversationId, PageRequest page) {
        Path dir = messagesDir(conversationId);
        if (!Files.exists(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .map(p -> read(p, conversationId))
                    .skip(page.offset())
                    .limit(page.size())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Message> findById(ConversationId conversationId, MessageId id) {
        Path dir = messagesDir(conversationId);
        if (!Files.exists(dir)) return Optional.empty();
        // Use glob to match "*_{messageId}.json" — avoids full directory scan
        PathMatcher matcher = dir.getFileSystem()
                .getPathMatcher("glob:**/*_" + id.value() + ".json");
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(matcher::matches)
                    .findFirst()
                    .map(p -> read(p, conversationId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Message append(ConversationId conversationId, IntFunction<Message> create) {
        ReentrantLock lock = appendLocks[Math.floorMod(conversationId.hashCode(), APPEND_LOCKS)];
        lock.lock();
        try {
            return save(create.apply(maxSequenceNum(conversationId) + 1));
        } finally {
            lock.unlock();
        }
    }

    /**
     * The highest number in the conversation, 0 when it has no messages.
     * Read from the file names, which carry it zero-padded in front, so
     * this costs a directory listing and opens nothing.
     */
    private int maxSequenceNum(ConversationId conversationId) {
        Path dir = messagesDir(conversationId);
        if (!Files.exists(dir)) return 0;
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".json"))
                    .mapToInt(p -> seqOf(p.getFileName().toString()))
                    .filter(seq -> seq >= 0)
                    .max().orElse(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The leading digits of "0000000012_<uuid>.json"; -1 for a name of another shape. */
    private static int seqOf(String fileName) {
        int underscore = fileName.indexOf('_');
        if (underscore < 1) return -1;
        try {
            return Integer.parseInt(fileName.substring(0, underscore));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public void deleteBySequenceRange(ConversationId conversationId, int fromSeq, int toSeq) {
        Path dir = messagesDir(conversationId);
        if (!Files.exists(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        int seq = seqOf(p.getFileName().toString());
                        if (seq >= 0 && seq >= fromSeq && seq <= toSeq) {
                            try {
                                Files.delete(p);
                                log.fine("Deleted message file: " + p.getFileName());
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Path messagesDir(ConversationId conversationId) {
        return baseDir.resolve(conversationId.value()).resolve("messages");
    }

    private Path fileFor(Message message) {
        String name = String.format("%010d_%s.json", message.sequenceNum(), message.id().value());
        return messagesDir(message.conversationId()).resolve(name);
    }

    /** A message written before the namespace was recorded takes it from the conversation asked for. */
    private Message read(Path file, ConversationId conversationId) {
        try {
            return objectMapper.readerFor(Message.class)
                    .readValue(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
