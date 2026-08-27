package ai.mindconnect.agent.memory.port.out;

import ai.mindconnect.agent.memory.domain.ConversationSummary;

import java.util.List;
import java.util.UUID;

/**
 * Persists and retrieves {@link ConversationSummary} records for a conversation.
 * Summaries are ordered by {@code fromSequenceNum} ascending.
 */
public interface ConversationSummaryRepository {

    /** Saves a new summary (never updates — summaries are immutable once created). */
    void save(ConversationSummary summary);

    /**
     * Returns all summaries for the given conversation,
     * ordered by {@code fromSequenceNum} ascending.
     */
    List<ConversationSummary> findByConversationId(UUID conversationId);

    /** Deletes all summaries for the given conversation (e.g. on session reset). */
    void deleteByConversationId(UUID conversationId);
}
