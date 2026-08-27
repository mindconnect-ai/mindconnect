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
        /** When the adapter started building the HTTP request. */
        Instant startedAt,
        /** Wall-clock duration from start to last byte (or to error). */
        long durationMs,
        /** The {@code llm-config} the request was routed against. */
        String llmConfigName,
        /** Resolved provider model name (e.g. {@code gpt-5.4-mini}). */
        String modelName,
        /** Prompt tokens reported by the provider, or 0 if unknown. */
        int promptTokens,
        /** Completion tokens reported by the provider, or 0 if unknown. */
        int completionTokens,
        /** Provider-reported finish reason ({@code stop}, {@code tool_calls}, …) or {@code null}. */
        String finishReason,
        /** Verbatim request body the adapter sent. Provider-specific JSON. */
        String requestJson,
        /**
         * Verbatim SSE event blocks the provider streamed back, in arrival
         * order. One element per blank-line-separated event block as defined
         * by the SSE spec. Empty for failed calls (see {@link #errorBody}).
         */
        List<String> responseEvents,
        /**
         * Reconstructed final assistant message — text + tool calls — built
         * by the adapter from the streamed deltas. Provider-agnostic shape;
         * makes the response readable at a glance. {@code null} if the call
         * failed before any chunks arrived.
         */
        ResponseSummary response,
        /** HTTP status if the call failed, otherwise {@code null}. */
        Integer errorStatus,
        /** Provider error body if the call failed, otherwise {@code null}. */
        String errorBody
) {

    /**
     * Compact, provider-agnostic view of what the model actually said.
     * Built from the streamed deltas, not from a separate response body —
     * the streaming API has no other source of truth.
     */
    public record ResponseSummary(
            /**
             * Accumulated assistant text. {@code null} if the model only
             * emitted tool calls (no prose).
             */
            String text,
            /**
             * Tool calls the model requested, fully reassembled from
             * streamed deltas. Empty list if none.
             */
            List<ToolCall> toolCalls
    ) {}
}
