package ai.mindconnect.llm.port.out;

import ai.mindconnect.common.Cancellation;
import ai.mindconnect.llm.domain.FinishReason;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmResponse;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.domain.ToolCall;
import ai.mindconnect.llm.port.in.LlmCallListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Adapter port to a concrete LLM provider. Streaming is the only mode — the
 * caller decides whether to surface tokens to a UI in real time or accumulate
 * them into a single response by consuming the {@link LlmStreamChunk}s.
 */
public interface LlmGateway {

    /**
     * Streams a chat completion. The handler receives chunks in arrival order
     * and the stream always ends with exactly one {@link LlmStreamChunk.Done}.
     * <p>
     * If {@code cancellation.isCancelled()} flips during the call (typically
     * because the caller invoked {@link Cancellation#cancel()} from another
     * thread), the gateway aborts the underlying HTTP connection and returns
     * promptly. The handler may receive a partial Text/ToolCall delta but
     * is not guaranteed a {@code Done}.
     * <p>
     * The {@code listener} is invoked exactly once after the call ends
     * (successfully or with an error) with the verbatim provider wire bodies.
     * The adapter is expected to capture the JSON it sent, accumulate the
     * raw response (or capture the error body), and fire the event from a
     * {@code finally} block. Listener exceptions must be swallowed — a
     * broken trace must never tear down the actual chat.
     */
    void chatStreaming(LlmConfig config, LlmRequest request,
                       Consumer<LlmStreamChunk> handler,
                       Cancellation cancellation,
                       LlmCallListener listener);

    /**
     * Blocking request/response wrapper around {@link #chatStreaming}.
     * Streams the call on the caller's thread, accumulates every
     * {@link LlmStreamChunk.TextDelta} into one string and every
     * {@link LlmStreamChunk.ToolCallDelta} into a list of completed
     * {@link ToolCall}s, then returns once the {@link LlmStreamChunk.Done}
     * frame arrives.
     *
     * <p>Default-implemented so providers can opt out by overriding only
     * when they have a cheaper truly-non-streaming endpoint. Cancellation
     * and listener semantics match {@link #chatStreaming}.
     */
    default LlmResponse chat(LlmConfig config, LlmRequest request,
                              Cancellation cancellation,
                              LlmCallListener listener) {
        StringBuilder text = new StringBuilder();
        Map<Integer, ToolCallAccumulator> tools = new TreeMap<>();
        FinishReason[] finish = { null };
        int[] inputTokens  = { 0 };
        int[] outputTokens = { 0 };

        chatStreaming(config, request, chunk -> {
            switch (chunk) {
                case LlmStreamChunk.TextDelta td -> text.append(td.text());
                case LlmStreamChunk.ToolCallDelta tcd -> tools
                        .computeIfAbsent(tcd.index(), i -> new ToolCallAccumulator())
                        .feed(tcd);
                // Non-streaming aggregation path (probes/tests) drops thinking
                // blocks — only the streaming tool loop needs them for replay.
                case LlmStreamChunk.ThinkingDelta ignored -> { }
                case LlmStreamChunk.Done done -> {
                    finish[0] = done.finishReason();
                    inputTokens[0]  = done.inputTokens();
                    outputTokens[0] = done.outputTokens();
                }
            }
        }, cancellation, listener);

        List<ToolCall> toolCalls = new ArrayList<>(tools.size());
        for (ToolCallAccumulator acc : tools.values()) {
            ToolCall built = acc.build();
            if (built != null) toolCalls.add(built);
        }
        return new LlmResponse(text.toString(), List.copyOf(toolCalls),
                finish[0], inputTokens[0], outputTokens[0]);
    }

    /**
     * Convenience overload — runs without a cancellation handle and with a
     * no-op listener. Use when neither matters (probes, tests, one-off
     * sanity checks). Throwing call sites should prefer the four-arg form
     * so they can plumb a real {@link Cancellation} through.
     */
    default LlmResponse chat(LlmConfig config, LlmRequest request) {
        return chat(config, request, Cancellation.none(), LlmCallListener.NOOP);
    }

    /**
     * Internal helper for {@link #chat}: stitches the streamed
     * {@link LlmStreamChunk.ToolCallDelta} fragments (id / name appear on
     * the first delta, arguments arrive as JSON-fragment chunks) back
     * into a single {@link ToolCall}. Per-provider streaming oddities are
     * already normalised at the chunk layer, so this naive concatenation
     * is enough.
     */
    final class ToolCallAccumulator {
        private String id;
        private String name;
        private final StringBuilder argsJson = new StringBuilder();

        void feed(LlmStreamChunk.ToolCallDelta d) {
            if (d.id() != null && !d.id().isEmpty()) this.id = d.id();
            if (d.name() != null && !d.name().isEmpty()) this.name = d.name();
            if (d.argumentsFragment() != null) this.argsJson.append(d.argumentsFragment());
        }

        ToolCall build() {
            if (id == null && name == null && argsJson.isEmpty()) return null;
            // Arguments are a JSON object string at this layer — callers
            // that need a typed Map<String,Object> parse it themselves
            // (same contract as the streaming-handler exposes today).
            return new ToolCall(id, name, parseArgs(argsJson.toString()));
        }

        private static Map<String, Object> parseArgs(String json) {
            // Empty fragment OR pure whitespace → empty map. We parse on a
            // best-effort basis to keep this default-implementation cheap;
            // a malformed-arguments call surfaces as "tool with empty
            // args" which the caller can then re-issue or fail.
            if (json == null || json.isBlank()) return Map.of();
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(json, Map.class);
                return parsed == null ? Map.of() : parsed;
            } catch (Exception e) {
                return Map.of();
            }
        }
    }
}
