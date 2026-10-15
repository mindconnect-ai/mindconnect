package ai.mindconnect.llm.domain;

import java.util.List;

/**
 * The result of a blocking chat call: the stream folded into one text plus the
 * complete tool calls (see {@link ai.mindconnect.llm.port.out.LlmGateway#chat}).
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
