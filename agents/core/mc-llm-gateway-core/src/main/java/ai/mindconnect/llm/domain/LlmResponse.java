package ai.mindconnect.llm.domain;

import java.util.List;

/**
 * Accumulated result of a non-streaming chat call (see
 * {@link ai.mindconnect.llm.port.out.LlmGateway#chat}).
 *
 * <p>This is what the default {@code chat(...)} helper hands back after
 * blocking on the underlying streaming call: every {@link LlmStreamChunk.TextDelta}
 * concatenated into one string, every {@link LlmStreamChunk.ToolCallDelta}
 * folded into a list of complete {@link ToolCall}s, plus the meta from the
 * single {@link LlmStreamChunk.Done} that ended the stream.
 *
 * @param text         the assistant's full reply text (may be empty if the
 *                     model only emitted tool calls)
 * @param toolCalls    the tool calls the model decided to make; empty list
 *                     when there are none
 * @param finishReason why the model stopped
 * @param inputTokens  prompt tokens reported by the provider
 * @param outputTokens completion tokens reported by the provider
 */
public record LlmResponse(
        String text,
        List<ToolCall> toolCalls,
        FinishReason finishReason,
        int inputTokens,
        int outputTokens
) {}
