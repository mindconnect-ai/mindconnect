package ai.mindconnect.agent.runtime.memory.port.out;

import ai.mindconnect.agent.runtime.memory.domain.ConversationSummary;
import ai.mindconnect.message.domain.ConversationId;

import java.util.List;

/**
 * Persists and retrieves {@link ConversationSummary} records for a conversation.
 * Summaries are ordered by {@code fromSequenceNum} ascending.
 */
public interface ConversationSummaryRepository {

    /** Saves a new summary (never updates — summaries are immutable once created). */
    void save(ConversationSummary summary);

    /** All summaries of the conversation, ordered by {@code fromSequenceNum} ascending. */
    List<ConversationSummary> findByConversation(ConversationId conversation);

    /** Deletes all summaries of the conversation (e.g. on session reset). */
    void deleteByConversation(ConversationId conversation);
}
