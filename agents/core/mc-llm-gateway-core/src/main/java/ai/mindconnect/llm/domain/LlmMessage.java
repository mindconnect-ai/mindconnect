package ai.mindconnect.llm.domain;

import java.util.List;
import java.util.stream.Collectors;

/**
 * One message of an LLM request. Its content is a list of {@link LlmContent}
 * blocks — for almost every message exactly one {@link LlmContent.Text}, for
 * a user message carrying images or documents a text block plus the media.
 * {@link #content()} is the text of those blocks, which is what every reader
 * that does not render media wants.
 *
 * @param parts          the content blocks; empty for an assistant turn that
 *                       only calls tools
 * @param thinkingBlocks reasoning blocks (with signatures) that preceded the
 *        tool calls in an assistant turn. Anthropic-specific; null for every
 *        other provider. Must be replayed before {@code toolCalls} in history
 *        (see {@link ThinkingBlock}).
 */
public record LlmMessage(MessageRole role, List<LlmContent> parts, String toolCallId,
                         List<ToolCall> toolCalls, List<ThinkingBlock> thinkingBlocks) {

    /** {@code parts} is never null, and never modifiable. */
    public LlmMessage {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    /**
     * The text of the message — its text blocks joined in order — or
     * {@code null} when there is no text block at all (an assistant turn that
     * only calls tools). Media blocks contribute nothing.
     */
    public String content() {
        List<String> texts = parts.stream()
                .filter(LlmContent.Text.class::isInstance)
                .map(p -> ((LlmContent.Text) p).text())
                .toList();
        return texts.isEmpty() ? null : String.join("\n", texts);
    }

    /** Does any block carry media — an image or a document? */
    public boolean hasMedia() {
        return parts.stream().anyMatch(p -> !(p instanceof LlmContent.Text));
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(MessageRole.SYSTEM, textBlocks(content), null, null, null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(MessageRole.USER, textBlocks(content), null, null, null);
    }

    /** A user message made of content blocks — text plus the images and documents sent with it. */
    public static LlmMessage user(List<LlmContent> parts) {
        return new LlmMessage(MessageRole.USER, parts, null, null, null);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(MessageRole.ASSISTANT, textBlocks(content), null, null, null);
    }

    public static LlmMessage assistantWithToolCalls(List<ToolCall> toolCalls) {
        return new LlmMessage(MessageRole.ASSISTANT, null, null, toolCalls, null);
    }

    /** Assistant turn carrying thinking blocks that preceded the tool calls. */
    public static LlmMessage assistantWithToolCalls(List<ThinkingBlock> thinkingBlocks,
                                                    List<ToolCall> toolCalls) {
        return new LlmMessage(MessageRole.ASSISTANT, null, null, toolCalls, thinkingBlocks);
    }

    public static LlmMessage tool(String toolCallId, String content) {
        return new LlmMessage(MessageRole.TOOL, textBlocks(content), toolCallId, null, null);
    }

    /** A plain string as its one text block; {@code null} text is no block at all. */
    private static List<LlmContent> textBlocks(String content) {
        return content == null ? List.of() : List.of(new LlmContent.Text(content));
    }

    @Override
    public String toString() {
        return "LlmMessage[role=" + role + ", content=" + content()
                + (hasMedia() ? ", media=" + parts.stream().filter(p -> !(p instanceof LlmContent.Text))
                        .map(p -> p.getClass().getSimpleName()).collect(Collectors.joining(",")) : "")
                + (toolCallId != null ? ", toolCallId=" + toolCallId : "")
                + (toolCalls != null ? ", toolCalls=" + toolCalls : "")
                + (thinkingBlocks != null ? ", thinkingBlocks=" + thinkingBlocks : "") + "]";
    }
}
