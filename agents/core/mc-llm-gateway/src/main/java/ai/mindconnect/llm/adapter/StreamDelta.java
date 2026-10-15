package ai.mindconnect.llm.adapter;

import ai.mindconnect.llm.domain.FinishReason;

/**
 * What one SSE block contributed to the answer, as the gateway's stream
 * loop books it: the text that went to the handler, how many text deltas,
 * and the finish reason and token counts when the block carried them.
 *
 * @param endOfStream the block said the stream is over, as OpenAI's {@code [DONE]} does
 */
public record StreamDelta(String text, int textDeltas, FinishReason finishReason,
                          int inputTokens, int outputTokens, boolean endOfStream) {

    public static final StreamDelta NONE = new StreamDelta(null, 0, null, 0, 0, false);
    public static final StreamDelta END = new StreamDelta(null, 0, null, 0, 0, true);
}
