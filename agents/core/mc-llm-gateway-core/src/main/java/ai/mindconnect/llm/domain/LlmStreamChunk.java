package ai.mindconnect.llm.domain;

/**
 * One chunk of an {@code LlmGateway.chatStreaming} response. The handler
 * receives a sequence of these in arrival order; the stream always ends with
 * exactly one {@link Done}.
 * <p>
 * Tool-call arguments arrive in fragments — multiple {@link ToolCallDelta}s
 * with the same {@code index} accumulate name and arguments incrementally,
 * matching the OpenAI streaming wire format.
 */
public sealed interface LlmStreamChunk
        permits LlmStreamChunk.TextDelta, LlmStreamChunk.ToolCallDelta,
                LlmStreamChunk.ThinkingDelta, LlmStreamChunk.Done {

    /** A piece of assistant text. Always non-empty. */
    record TextDelta(String text) implements LlmStreamChunk {}

    /**
     * A fragment of a reasoning ("thinking") block. {@code index} groups
     * fragments of the same block. {@code type} ("thinking" |
     * "redacted_thinking") arrives with the first fragment; {@code textFragment}
     * accumulates readable reasoning; {@code signature} and {@code data} arrive
     * once. Anthropic-specific — see {@link ThinkingBlock}.
     */
    record ThinkingDelta(int index, String type, String textFragment,
                         String signature, String data)
            implements LlmStreamChunk {}

    /**
     * A fragment of a tool call. {@code index} groups fragments belonging to
     * the same call (the LLM may stream multiple parallel calls verzahnt).
     * Any of {@code id}, {@code name}, {@code argumentsFragment} may be null
     * — they are filled across multiple deltas as the LLM produces them.
     * <p>
     * {@code thoughtSignature} is a Gemini-specific opaque token that must be
     * echoed back in conversation history (see {@link ToolCall}); null for all
     * other providers.
     */
    record ToolCallDelta(int index, String id, String name, String argumentsFragment,
                         String thoughtSignature)
            implements LlmStreamChunk {

        /** Delta without a thought signature (OpenAI, Anthropic). */
        public ToolCallDelta(int index, String id, String name, String argumentsFragment) {
            this(index, id, name, argumentsFragment, null);
        }
    }

    /**
     * Terminal chunk. {@code finishReason} tells the caller whether the model
     * stopped normally ({@link FinishReason#STOP}) or wants to call tools
     * ({@link FinishReason#TOOL_CALLS}). Token counts are populated when the
     * provider reports usage; otherwise they are 0.
     */
    record Done(FinishReason finishReason, int inputTokens, int outputTokens)
            implements LlmStreamChunk {}
}
