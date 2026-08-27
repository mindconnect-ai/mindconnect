package ai.mindconnect.message.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Message(
        UUID id,
        UUID conversationId,
        UUID senderId,
        ParticipantType senderType,
        UUID recipientId,
        MessageType type,
        String content,
        Map<String, Object> metadata,
        int sequenceNum,
        Instant sentAt,
        boolean compressed,
        String compressedContent,
        Integer tokenCount,
        Integer compressedTokenCount,
        /**
         * Wall-clock duration that produced this message, in milliseconds.
         * Meaningful for outputs the agent process generated:
         *   • {@link MessageType#TOOL_RESULT} → tool execution time
         *   • {@link MessageType#CHAT} from agent → LLM round-trip latency
         * Null for inputs ({@link ParticipantType#USER} CHAT, {@link MessageType#TOOL_CALL}).
         */
        Long durationMs,
        /**
         * Identifier of the agent chat-turn that produced this message.
         * <p>
         * One turn produces one user CHAT (the prompt) plus all the
         * assistant TOOL_CALL / TOOL_RESULT / CHAT messages emitted by the
         * tool-loop until the final answer. All of them share the same
         * {@code turnId}.
         * <p>
         * {@code null} on:
         *   • messages persisted before this field existed (no backfill)
         *   • messages created outside an agent turn (e.g. broadcast)
         */
        UUID turnId,
        /**
         * Which LOOP RUN of the turn wrote this message: 0 for the first
         * execution, 1+ for approval resumes. Together with {@link #turnId}
         * (the logical user→assistant turn) this names the task execution
         * deterministically — {@code task_turn_<turnId>} for run 0,
         * {@code task_turn_<turnId>_r<run>} after. {@code null} on messages
         * persisted before this field existed; read it as 0.
         */
        Integer run
) {
    public static Message of(UUID conversationId, UUID senderId, ParticipantType senderType,
                             MessageType type, String content, int seq) {
        return new Message(UUID.randomUUID(), conversationId, senderId, senderType, null,
                type, content, Map.of(), seq, Instant.now(), false, null, null, null, null, null, null);
    }

    /**
     * Returns a copy with the given metadata. For the tool/approval types the
     * keys are structural, not decorative — {@code callId}/{@code requestId}
     * are what the runtime's derivation pairs over (see {@link MessageType}),
     * so they belong here, readable without parsing {@code content}.
     */
    public Message withMetadata(Map<String, Object> metadata) {
        return new Message(id, conversationId, senderId, senderType, recipientId,
                type, content, Map.copyOf(metadata), sequenceNum, sentAt, compressed, compressedContent,
                tokenCount, compressedTokenCount, durationMs, turnId, run);
    }

    /**
     * Returns a copy of this message marked as compressed.
     * {@code compressedContent} is the stub shown to the LLM in subsequent turns.
     * The original {@code content} is preserved for audit and debugging.
     */
    public Message withCompressed(String stub, Integer compressedTokens) {
        return new Message(id, conversationId, senderId, senderType, recipientId,
                type, content, metadata, sequenceNum, sentAt, true, stub,
                tokenCount, compressedTokens, durationMs, turnId, run);
    }

    /** Returns a copy of this message with the token count set. */
    public Message withTokenCount(int tokens) {
        return new Message(id, conversationId, senderId, senderType, recipientId,
                type, content, metadata, sequenceNum, sentAt, compressed, compressedContent,
                tokens, compressedTokenCount, durationMs, turnId, run);
    }

    /** Returns a copy of this message with the wall-clock duration set. */
    public Message withDurationMs(long durationMs) {
        return new Message(id, conversationId, senderId, senderType, recipientId,
                type, content, metadata, sequenceNum, sentAt, compressed, compressedContent,
                tokenCount, compressedTokenCount, durationMs, turnId, run);
    }

    /** The run as a primitive — {@code null} (legacy) reads as 0. */
    public int runOrZero() {
        return run == null ? 0 : run;
    }

    /** Returns a copy stamped with the loop run that wrote this message. */
    public Message withRun(int run) {
        return new Message(id, conversationId, senderId, senderType, recipientId,
                type, content, metadata, sequenceNum, sentAt, compressed, compressedContent,
                tokenCount, compressedTokenCount, durationMs, turnId, run);
    }

    /** Returns a copy of this message tagged with the given chat-turn id. */
    public Message withTurnId(UUID turnId) {
        return new Message(id, conversationId, senderId, senderType, recipientId,
                type, content, metadata, sequenceNum, sentAt, compressed, compressedContent,
                tokenCount, compressedTokenCount, durationMs, turnId, run);
    }
}
