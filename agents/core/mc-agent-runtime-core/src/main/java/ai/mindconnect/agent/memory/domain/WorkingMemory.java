package ai.mindconnect.agent.memory.domain;

import java.util.List;

/**
 * Snapshot of what is currently in / will be sent to the LLM as context.
 * Used by the /memory endpoint and CLI /memory command.
 */
public record WorkingMemory(
        /** The fully-built system prompt text (after workspace injection). */
        String systemPrompt,
        /** Token count for the system prompt. */
        int systemPromptTokens,
        /** The messages that will be included in the next LLM call (the window). */
        List<WorkingMemoryMessage> messages,
        /** Total token count across system prompt + all window messages. */
        int totalTokens,
        /** Name of the token-counting strategy used (e.g. "JtokkitTokenCounter", "CharBasedTokenCounter"). */
        String tokenCounterName,
        /** Maximum context window of the configured LLM in tokens. Null if unknown. */
        Integer contextWindowTokens
) {

    public record WorkingMemoryMessage(
            /** CHAT / TOOL_CALL / TOOL_RESULT / SYSTEM etc. */
            String type,
            /** USER / AGENT */
            String role,
            /** Sequence number within the conversation. */
            int sequenceNum,
            /** Epoch-millis of when the message was sent. */
            long sentAtMs,
            /** Token count for this message (based on what the LLM actually sees). */
            int tokens,
            /** Full original message content. */
            String content,
            /** Whether this message has been compressed. */
            boolean compressed,
            /** The compressed stub shown to the LLM instead of the full content. Null if not compressed. */
            String compressedContent
    ) {}
}
