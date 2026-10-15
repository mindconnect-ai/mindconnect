package ai.mindconnect.llm.domain;

import java.time.Instant;
import java.util.List;

/**
 * One observation of a provider call, reported to the
 * {@link ai.mindconnect.llm.port.in.LlmCallListener} whether the call succeeded or
 * not. It carries the verbatim wire request and the verbatim SSE blocks because
 * that is what explains a cryptic 400 or an uninterpretable tool call;
 * {@link #response} is the runtime's own reconstruction for quick reading. Token
 * counts and the finish reason are 0 or null when the call failed before usage was
 * reported, so a listener must not rely on them.
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
