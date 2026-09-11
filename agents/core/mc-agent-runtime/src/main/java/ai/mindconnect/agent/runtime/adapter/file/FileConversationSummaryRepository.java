package ai.mindconnect.agent.runtime.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.runtime.memory.domain.ConversationSummary;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.filerepo.FileRepo;
import ai.mindconnect.filerepo.FileRepoException;
import ai.mindconnect.filerepo.RecordLog;
import ai.mindconnect.message.domain.ConversationId;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Stores conversation summaries one file per summary:
 *   {base}/conversations/{conversationId}/summaries/{fromSequenceNum}_{summaryId}.json
 *
 * <p>Saving a summary writes its own file and touches no other, so two
 * compactions of the same conversation cannot drop each other's summary — the
 * earlier layout, one {@code summaries.json} array rewritten on every save, could.
 * Summaries stored in that file are not read any more.
 */
public class FileConversationSummaryRepository implements ConversationSummaryRepository {

    private static final Logger log = LoggerFactory.getLogger(FileConversationSummaryRepository.class);

    private final RecordLog<ConversationId, ConversationSummary> summaries;

    public FileConversationSummaryRepository(Path baseDir, Namespace namespace) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.summaries = RecordLog.of(ConversationSummary.class)
                .dir((ConversationId conversation) -> "conversations/" + conversation.value() + "/summaries")
                .key(ConversationSummary::fromSequenceNum)
                .id(s -> s.id().value())
                .prettyPrint()
                .build(FileRepo.open(baseDir, namespace.value()), mapper);
    }

    @Override
    public void save(ConversationSummary summary) {
        summaries.put(summary.conversationId(), summary);
        log.debug("Saved summary {} for conversation {} (seq {}-{})",
                summary.id(), summary.conversationId(),
                summary.fromSequenceNum(), summary.toSequenceNum());
    }

    /**
     * Ordered by {@code fromSequenceNum}. An unreadable summary counts as none —
     * the turn goes on without the summaries rather than failing; nothing is
     * rewritten from what failed to load.
     */
    @Override
    public List<ConversationSummary> findByConversation(ConversationId conversationId) {
        try {
            return summaries.all(conversationId);
        } catch (FileRepoException e) {
            log.warn("Failed to read summaries of conversation {}: {}", conversationId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void deleteByConversation(ConversationId conversationId) {
        summaries.deleteAll(conversationId);
    }
}
