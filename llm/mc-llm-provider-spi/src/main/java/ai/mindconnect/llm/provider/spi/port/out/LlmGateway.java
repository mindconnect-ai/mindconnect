package ai.mindconnect.llm.provider.spi.port.out;

import ai.mindconnect.llm.runtime.api.domain.Cancellation;
import ai.mindconnect.llm.runtime.api.domain.LlmCallEvent;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.runtime.api.domain.LlmRequest;
import ai.mindconnect.llm.runtime.api.domain.LlmResponse;
import ai.mindconnect.llm.runtime.api.domain.LlmResponseCollector;
import ai.mindconnect.llm.runtime.api.domain.LlmStreamChunk;
import ai.mindconnect.llm.runtime.api.port.out.LlmCallListener;

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
        LlmResponseCollector collector = new LlmResponseCollector();
        chatStreaming(config, request, collector::accept, cancellation, event -> {
            collector.onCall(event);
            if (listener != null) listener.onCall(event);
        });
        return collector.response();
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
