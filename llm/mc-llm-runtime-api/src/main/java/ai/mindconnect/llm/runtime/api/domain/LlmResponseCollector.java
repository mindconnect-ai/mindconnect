package ai.mindconnect.llm.runtime.api.domain;

import java.util.List;

/**
 * Folds one streamed chat call into an {@link LlmResponse}, for the blocking
 * {@code chat} of both {@code LlmChat} and {@code LlmGateway}. Feed it every
 * chunk and the call event. The tool calls come from the event, because the
 * adapter has already reassembled and parsed them there; the deltas are only
 * fragments. Thinking is dropped: only the streaming tool loop replays it.
 * One instance per call, used on the calling thread.
 */
public final class LlmResponseCollector {

    private final StringBuilder text = new StringBuilder();
    private FinishReason finishReason;
    private int inputTokens;
    private int outputTokens;
    private LlmCallEvent call;

    public void accept(LlmStreamChunk chunk) {
        switch (chunk) {
            case LlmStreamChunk.TextDelta delta -> text.append(delta.text());
            case LlmStreamChunk.ToolCallDelta ignored -> { }
            case LlmStreamChunk.ThinkingDelta ignored -> { }
            case LlmStreamChunk.Done done -> {
                finishReason = done.finishReason();
                inputTokens = done.inputTokens();
                outputTokens = done.outputTokens();
            }
        }
    }

    public void onCall(LlmCallEvent event) {
        call = event;
    }

    public LlmResponse response() {
        List<ToolCall> toolCalls = call == null || call.response() == null
                ? List.of() : List.copyOf(call.response().toolCalls());
        return new LlmResponse(text.toString(), toolCalls, finishReason, inputTokens, outputTokens);
    }
}
