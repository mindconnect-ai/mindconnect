package ai.mindconnect.agent.memory.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a summarized range of messages in a conversation.
 * <p>
 * Messages with a sequence number between {@code fromSequenceNum} and {@code toSequenceNum}
 * (inclusive) are covered by this summary and should be excluded from the LLM context window —
 * the summary itself is injected into the system prompt instead.
 * <p>
 * Multiple summaries per conversation are supported. Each compress pass appends a new summary
 * covering messages after the last summarized sequence number.
 */
public record ConversationSummary(
        UUID id,
        UUID conversationId,
        int fromSequenceNum,   // inclusive
        int toSequenceNum,     // inclusive
        int messageCount,
        String content,
        Instant createdAt
) {
    public static ConversationSummary create(UUID conversationId,
                                             int fromSequenceNum,
                                             int toSequenceNum,
                                             int messageCount,
                                             String content) {
        return new ConversationSummary(
                UUID.randomUUID(),
                conversationId,
                fromSequenceNum,
                toSequenceNum,
                messageCount,
                content,
                Instant.now()
        );
    }

    /** Returns true if the given sequence number falls within this summary's range. */
    public boolean covers(int sequenceNum) {
        return sequenceNum >= fromSequenceNum && sequenceNum <= toSequenceNum;
    }
}
