package ai.mindconnect.llm.domain;

import java.util.List;

/**
 * @param thinkingBlocks reasoning blocks (with signatures) that preceded the
 *        tool calls in an assistant turn. Anthropic-specific; null for every
 *        other provider. Must be replayed before {@code toolCalls} in history
 *        (see {@link ThinkingBlock}).
 */
public record LlmMessage(MessageRole role, String content, String toolCallId,
                         List<ToolCall> toolCalls, List<ThinkingBlock> thinkingBlocks) {

    /** Compatibility constructor for messages without thinking blocks. */
    public LlmMessage(MessageRole role, String content, String toolCallId, List<ToolCall> toolCalls) {
        this(role, content, toolCallId, toolCalls, null);
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(MessageRole.SYSTEM, content, null, null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(MessageRole.USER, content, null, null);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(MessageRole.ASSISTANT, content, null, null);
    }

    public static LlmMessage assistantWithToolCalls(List<ToolCall> toolCalls) {
        return new LlmMessage(MessageRole.ASSISTANT, null, null, toolCalls);
    }

    /** Assistant turn carrying thinking blocks that preceded the tool calls. */
    public static LlmMessage assistantWithToolCalls(List<ThinkingBlock> thinkingBlocks,
                                                    List<ToolCall> toolCalls) {
        return new LlmMessage(MessageRole.ASSISTANT, null, null, toolCalls, thinkingBlocks);
    }

    public static LlmMessage tool(String toolCallId, String content) {
        return new LlmMessage(MessageRole.TOOL, content, toolCallId, null);
    }
}
