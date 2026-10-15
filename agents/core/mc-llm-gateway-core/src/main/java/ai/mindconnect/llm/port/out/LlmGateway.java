package ai.mindconnect.llm.port.out;

import ai.mindconnect.common.Cancellation;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.port.in.LlmCallListener;

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
}
