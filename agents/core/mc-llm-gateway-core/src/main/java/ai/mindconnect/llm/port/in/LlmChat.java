package ai.mindconnect.llm.port.in;

import ai.mindconnect.common.Cancellation;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;

import java.util.function.Consumer;

public interface LlmChat {

    /**
     * Streams a chat completion. The handler receives chunks in arrival order
     * and the stream ends with exactly one {@link LlmStreamChunk.Done} unless
     * {@code cancellation} fires, in which case the stream may end abruptly.
     *
     * <p>The {@code listener} is invoked exactly once after the call ends —
     * successfully or with an error — with the verbatim provider wire bodies.
     * Use {@link LlmCallListener#NOOP} to skip tracing.
     */
    void chatStreaming(LlmRequest request,
                       Consumer<LlmStreamChunk> handler,
                       Cancellation cancellation,
                       LlmCallListener listener);

    /** Convenience: no listener (NOOP). */
    default void chatStreaming(LlmRequest request,
                                Consumer<LlmStreamChunk> handler,
                                Cancellation cancellation) {
        chatStreaming(request, handler, cancellation, LlmCallListener.NOOP);
    }

    /** Convenience: no cancellation, no listener. */
    default void chatStreaming(LlmRequest request, Consumer<LlmStreamChunk> handler) {
        chatStreaming(request, handler, Cancellation.none(), LlmCallListener.NOOP);
    }
}
