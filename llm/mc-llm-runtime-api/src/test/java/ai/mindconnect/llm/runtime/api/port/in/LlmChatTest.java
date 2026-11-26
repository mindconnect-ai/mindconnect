package ai.mindconnect.llm.runtime.api.port.in;

import ai.mindconnect.llm.runtime.api.domain.Cancellation;
import ai.mindconnect.llm.runtime.api.domain.FinishReason;
import ai.mindconnect.llm.runtime.api.domain.LlmCallEvent;
import ai.mindconnect.llm.runtime.api.domain.LlmMessage;
import ai.mindconnect.llm.runtime.api.domain.LlmRequest;
import ai.mindconnect.llm.runtime.api.domain.LlmResponse;
import ai.mindconnect.llm.runtime.api.domain.LlmStreamChunk;
import ai.mindconnect.llm.runtime.api.domain.ToolCall;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmChatTest {

    private static final ToolCall WEATHER = new ToolCall("call_1", "weather", Map.of("city", "Berlin"));

    /** A chat that streams two text deltas, a tool call fragment and the end, then reports the call. */
    private static final LlmChat STREAMING = (request, handler, cancellation, listener) -> {
        handler.accept(new LlmStreamChunk.TextDelta("Hello, "));
        handler.accept(new LlmStreamChunk.TextDelta("world"));
        handler.accept(new LlmStreamChunk.ToolCallDelta(0, "call_1", "weather", "{\"ci", null));
        handler.accept(new LlmStreamChunk.Done(FinishReason.TOOL_CALLS, 12, 7));
        listener.onCall(new LlmCallEvent(Instant.now(), 5, request.configName(), "m", 12, 7, "tool_calls",
                "{}", List.of(), new LlmCallEvent.ResponseSummary("Hello, world", List.of(WEATHER)), null, null));
    };

    @Test
    void the_blocking_chat_returns_the_whole_answer_with_the_parsed_tool_calls() {
        LlmResponse response = STREAMING.chat(LlmRequest.of("chat", List.of(LlmMessage.user("hi"))));

        assertThat(response).isEqualTo(new LlmResponse("Hello, world", List.of(WEATHER), FinishReason.TOOL_CALLS, 12, 7));
    }

    @Test
    void the_blocking_chat_hands_the_call_event_to_the_listener_too() {
        List<LlmCallEvent> events = new ArrayList<>();

        STREAMING.chat(LlmRequest.of("chat", List.of(LlmMessage.user("hi"))),
                Cancellation.none(), events::add);

        assertThat(events).singleElement().extracting(LlmCallEvent::llmConfigName).isEqualTo("chat");
    }
}
