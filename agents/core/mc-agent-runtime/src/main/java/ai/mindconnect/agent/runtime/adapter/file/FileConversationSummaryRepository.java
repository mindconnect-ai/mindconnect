package ai.mindconnect.agent.runtime.adapter.file;

import ai.mindconnect.common.util.AtomicFiles;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.message.domain.ConversationId;

import ai.mindconnect.agent.runtime.memory.domain.ConversationSummary;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stores conversation summaries under:
 *   {base}/conversations/{conversationId}/summaries.json
 *
 * The file is a JSON array of {@link ConversationSummary} records,
 * ordered by {@code fromSequenceNum} ascending.
 */
public class FileConversationSummaryRepository implements ConversationSummaryRepository {

    private static final Logger log = LoggerFactory.getLogger(FileConversationSummaryRepository.class);
    private static final String FILE_NAME = "summaries.json";
    private static final TypeReference<List<ConversationSummary>> LIST_TYPE = new TypeReference<>() {};

    private final Path baseDir;
    private final ObjectMapper mapper;

    public FileConversationSummaryRepository(Path baseDir, Namespace namespace) {
        this.baseDir = baseDir.resolve(namespace.value()).toAbsolutePath().normalize();
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public void save(ConversationSummary summary) {
        Path file = fileFor(summary.conversationId());
        try {
            // Read strictly: rewriting the file from a list that failed to load
            // would replace every earlier summary with just this one.
            List<ConversationSummary> existing = Files.exists(file) ? read(file) : new ArrayList<>();
            existing.add(summary);
            existing.sort(Comparator.comparingInt(ConversationSummary::fromSequenceNum));
            AtomicFiles.write(file, out -> mapper.writerWithDefaultPrettyPrinter().writeValue(out, existing));
            log.debug("Saved summary {} for conversation {} (seq {}-{})",
                    summary.id(), summary.conversationId(),
                    summary.fromSequenceNum(), summary.toSequenceNum());
        } catch (IOException e) {
            log.warn("Failed to save summary for conversation {} (existing summaries left untouched): {}",
                    summary.conversationId(), e.getMessage());
        }
    }

    @Override
    public List<ConversationSummary> findByConversation(ConversationId conversationId) {
        Path file = fileFor(conversationId);
        if (!Files.exists(file)) return List.of();
        return load(file);
    }

    @Override
    public void deleteByConversation(ConversationId conversationId) {
        Path file = fileFor(conversationId);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete summaries for conversation {}: {}", conversationId, e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Path fileFor(ConversationId conversationId) {
        return baseDir
                .resolve("conversations")
                .resolve(conversationId.value())
                .resolve(FILE_NAME);
    }

    /** For reading: an unreadable file counts as no summaries, the turn goes on without them. */
    private List<ConversationSummary> load(Path file) {
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            return read(file);
        } catch (IOException e) {
            log.warn("Failed to read summaries from {}: {}", file, e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<ConversationSummary> read(Path file) throws IOException {
        List<ConversationSummary> list = mapper.readerFor(LIST_TYPE).readValue(file.toFile());
        list.sort(Comparator.comparingInt(ConversationSummary::fromSequenceNum));
        return new ArrayList<>(list);
    }
}
