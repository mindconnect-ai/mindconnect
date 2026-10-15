package ai.mindconnect.llm.domain;

import java.time.Instant;
import java.util.List;

/**
 * Single observation of one provider call. Emitted to the {@link
 * ai.mindconnect.llm.port.in.LlmCallListener} a caller passes to
 * {@code chatStreaming(...)}, regardless of whether the call succeeded.
 *
 * <p>The event is deliberately provider-agnostic in its <em>shape</em> but
 * provider-specific in its <em>contents</em>: {@link #requestJson} holds
 * the verbatim wire request body, {@link #responseEvents} the verbatim
 * SSE event blocks the provider sent. That's what you want when the
 * provider rejects a request with a cryptic 400 or returns a tool call
 * you can't interpret. {@link #response} is the runtime's <em>own</em>
 * reconstruction of the final assistant message — text + tool calls
 * reassembled from the streamed deltas — for quick human reading.
 *
 * <p>Fields {@code promptTokens}, {@code completionTokens}, and
 * {@code finishReason} may be {@code 0} / {@code null} when the call
 * failed before usage was reported, or when the provider didn't supply
 * the data. The listener implementation must not assume they are present.
 */
public record LlmCallEvent(
        Instant startedAt,
        /** From the start to the last byte, or to the error. */
        long durationMs,
        /** The config the request was routed against, by name; an alias stays an alias here. */
        String llmConfigName,
        /** The model the provider was asked for, placeholders resolved. */
        String modelName,
        /** Zero when the call failed before the provider reported usage. */
        int promptTokens,
        int completionTokens,
        String finishReason,
        /**
         * The request body the adapter sent, provider-specific JSON — with
         * one edit: an inline media payload (an image or PDF as base64) is
         * replaced by a note naming its media type and length, so a trace
         * does not grow by the size of every attachment.
         */
        String requestJson,
        /**
         * Verbatim SSE event blocks the provider streamed back, in arrival
         * order. One element per blank-line-separated event block as defined
         * by the SSE spec. Empty for failed calls (see {@link #errorBody}).
         */
        List<String> responseEvents,
        /** What the model said, reassembled from the deltas; {@code null} when nothing arrived before the call failed. */
        ResponseSummary response,
        /** The HTTP status and the provider's body when the call failed, else {@code null}. */
        Integer errorStatus,
        String errorBody
) {

    /**
     * What the model actually said, in one provider-agnostic shape. Built
     * from the streamed deltas, since the streaming API has no other source
     * of truth; {@code text} is {@code null} when the model only called tools.
     */
    public record ResponseSummary(String text, List<ToolCall> toolCalls) {}
}
