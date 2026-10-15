package ai.mindconnect.llm.port.out;

import ai.mindconnect.common.Cancellation;
import ai.mindconnect.llm.domain.FinishReason;
import ai.mindconnect.llm.domain.LlmCallEvent;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmResponse;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.domain.ToolCall;
import ai.mindconnect.llm.port.in.LlmCallListener;

import java.util.List;
import java.util.function.Consumer;

/**
 * Adapter port to a concrete LLM provider. Streaming is the only mode — the
 * caller decides whether to surface tokens to a UI in real time or accumulate
 * them into a single response by consuming the {@link LlmStreamChunk}s.
 */
public interface LlmGateway {

    /**
     * Streams a chat completion: chunks arrive in order and a completed stream ends
     * with exactly one {@link LlmStreamChunk.Done}. When the cancellation flips, the
     * gateway aborts the connection and returns promptly, so the handler may see a
     * partial delta and no {@code Done}. The listener is invoked exactly once after
     * the call ends, successfully or not, with the verbatim wire bodies; its
     * exceptions are swallowed, because a broken trace must never tear down the chat.
     */
    void chatStreaming(LlmConfig config, LlmRequest request,
                       Consumer<LlmStreamChunk> handler,
                       Cancellation cancellation,
                       LlmCallListener listener);

    /**
     * Blocking request/response wrapper around {@link #chatStreaming}: streams
     * the call on the caller's thread, joins every {@link LlmStreamChunk.TextDelta}
     * into one string and returns once the {@link LlmStreamChunk.Done} frame
     * arrives. The tool calls are the ones the adapter reassembled for its
     * {@link LlmCallEvent} — parsed there, with the runtime's JSON mapper, so
     * this port needs none of its own. Thinking blocks are dropped; only the
     * streaming tool loop replays them.
     *
     * <p>Default-implemented so a provider overrides it only when it has a
     * cheaper, truly non-streaming endpoint. Cancellation and listener
     * semantics match {@link #chatStreaming}.
     */
    default LlmResponse chat(LlmConfig config, LlmRequest request,
                              Cancellation cancellation,
                              LlmCallListener listener) {
        StringBuilder text = new StringBuilder();
        FinishReason[] finish = { null };
        int[] inputTokens  = { 0 };
        int[] outputTokens = { 0 };
        LlmCallEvent[] call = { null };

        chatStreaming(config, request, chunk -> {
            switch (chunk) {
                case LlmStreamChunk.TextDelta td -> text.append(td.text());
                case LlmStreamChunk.ToolCallDelta ignored -> { }
                case LlmStreamChunk.ThinkingDelta ignored -> { }
                case LlmStreamChunk.Done done -> {
                    finish[0] = done.finishReason();
                    inputTokens[0]  = done.inputTokens();
                    outputTokens[0] = done.outputTokens();
                }
            }
        }, cancellation, event -> {
            call[0] = event;
            if (listener != null) listener.onCall(event);
        });

        List<ToolCall> toolCalls = call[0] == null || call[0].response() == null
                ? List.of() : List.copyOf(call[0].response().toolCalls());
        return new LlmResponse(text.toString(), toolCalls, finish[0], inputTokens[0], outputTokens[0]);
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

}
