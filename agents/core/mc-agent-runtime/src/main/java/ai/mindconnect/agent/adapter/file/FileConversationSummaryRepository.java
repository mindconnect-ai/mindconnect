package ai.mindconnect.agent.adapter.file;

import ai.mindconnect.agent.memory.domain.ConversationSummary;
import ai.mindconnect.agent.memory.port.out.ConversationSummaryRepository;
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
import java.util.UUID;

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

    public FileConversationSummaryRepository(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public void save(ConversationSummary summary) {
        Path file = fileFor(summary.conversationId());
        try {
            Files.createDirectories(file.getParent());
            List<ConversationSummary> existing = load(file);
            existing.add(summary);
            existing.sort(Comparator.comparingInt(ConversationSummary::fromSequenceNum));
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), existing);
            log.debug("Saved summary {} for conversation {} (seq {}-{})",
                    summary.id(), summary.conversationId(),
                    summary.fromSequenceNum(), summary.toSequenceNum());
        } catch (IOException e) {
            log.warn("Failed to save summary for conversation {}: {}", summary.conversationId(), e.getMessage());
        }
    }

    @Override
    public List<ConversationSummary> findByConversationId(UUID conversationId) {
        Path file = fileFor(conversationId);
        if (!Files.exists(file)) return List.of();
        return load(file);
    }

    @Override
    public void deleteByConversationId(UUID conversationId) {
        Path file = fileFor(conversationId);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete summaries for conversation {}: {}", conversationId, e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Path fileFor(UUID conversationId) {
        return baseDir
                .resolve("conversations")
                .resolve(conversationId.toString())
                .resolve(FILE_NAME);
    }

    private List<ConversationSummary> load(Path file) {
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            List<ConversationSummary> list = mapper.readValue(file.toFile(), LIST_TYPE);
            list.sort(Comparator.comparingInt(ConversationSummary::fromSequenceNum));
            return new ArrayList<>(list);
        } catch (IOException e) {
            log.warn("Failed to read summaries from {}: {}", file, e.getMessage());
            return new ArrayList<>();
        }
    }
}
