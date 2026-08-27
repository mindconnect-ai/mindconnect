package ai.mindconnect.agent.domain;

import ai.mindconnect.llm.domain.LlmCallEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persisted record of one LLM provider call as observed from the
 * agent-runtime, combining the gateway-level {@link LlmCallEvent} (verbatim
 * wire bodies, tokens, finish reason) with the runtime-level
 * {@link TraceContext} (which conversation / session / turn it belongs to).
 *
 * <p>One per provider roundtrip. A single chat turn produces one trace per
 * tool-loop iteration. Sub-agent calls produce traces under their own
 * conversation id with {@code parentTurnId} pointing back at the parent.
 */
public record LlmCallTrace(
        /** Stable id for this trace, unique across the system. */
        UUID id,
        TraceContext context,
        Instant startedAt,
        long durationMs,
        String llmConfigName,
        String modelName,
        int promptTokens,
        int completionTokens,
        String finishReason,
        /** Verbatim provider wire request body (pretty-printed JSON). */
        String requestJson,
        /**
         * Verbatim SSE event blocks the provider streamed back, in arrival
         * order. One element per blank-line-separated event block.
         */
        List<String> responseEvents,
        /**
         * Reconstructed final assistant message — text + tool calls — for
         * a quick human reading. Built by the gateway adapter from the
         * stream deltas. {@code null} if the call failed.
         */
        LlmCallEvent.ResponseSummary response,
        /** HTTP status if the call failed, else null. */
        Integer errorStatus,
        /** Error body (provider JSON or exception message) if the call failed, else null. */
        String errorBody
) {

    /** Convenience constructor: combines a gateway {@link LlmCallEvent} with the runtime context. */
    public static LlmCallTrace of(TraceContext context, LlmCallEvent event) {
        return new LlmCallTrace(
                UUID.randomUUID(),
                context,
                event.startedAt(),
                event.durationMs(),
                event.llmConfigName(),
                event.modelName(),
                event.promptTokens(),
                event.completionTokens(),
                event.finishReason(),
                event.requestJson(),
                event.responseEvents(),
                event.response(),
                event.errorStatus(),
                event.errorBody());
    }
}
