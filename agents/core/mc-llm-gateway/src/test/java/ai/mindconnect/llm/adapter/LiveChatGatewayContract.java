package ai.mindconnect.llm.adapter;

import ai.mindconnect.common.Cancellation;
import ai.mindconnect.llm.domain.FinishReason;
import ai.mindconnect.llm.domain.LlmCallEvent;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmResponse;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.domain.ToolCall;
import ai.mindconnect.llm.domain.ToolDefinition;
import ai.mindconnect.llm.port.out.LlmGateway;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What every chat gateway does against its real provider: streams a reply,
 * streams a tool call, tells the listener about a rejected key, and stops
 * when the caller cancels. A subclass names the gateway and the config and
 * gates itself on {@link LiveModels}. Live models can answer off-script; a
 * rare red run means that, not a broken adapter.
 */
public abstract class LiveChatGatewayContract {

    protected abstract LlmGateway gateway();

    protected abstract LlmConfig config();

    /**
     * A client like the runtime's: no read timeout, because a reasoning model
     * sends its first byte after it has thought, which takes longer than
     * OkHttp's default ten seconds. The call timeout is the test's safety net.
     */
    protected static OkHttpClient httpClient() {
        return new OkHttpClient.Builder()
                .readTimeout(Duration.ZERO)
                .callTimeout(Duration.ofMinutes(3))
                .build();
    }

    /** A config for a hosted model: deterministic where the model takes a temperature, room for reasoning. */
    protected static LlmConfig config(LlmProvider provider, String model, String apiKey,
                                      Map<String, Object> additionalParams) {
        return new LlmConfig(LlmConfigId.random(), "live-" + provider.name().toLowerCase(), provider, model,
                null, apiKey, 0.0, 2048, additionalParams, null, false, null, null, null,
                LlmConfigType.CHAT, null);
    }

    @Test
    void streams_a_reply_and_reports_the_call_to_the_listener() {
        LlmConfig config = config();
        LlmRequest request = LlmRequest.streaming(config.name(), List.of(
                LlmMessage.system("Answer with a single word."),
                LlmMessage.user("What colour is a clear daytime sky?")));
        List<LlmStreamChunk> chunks = new ArrayList<>();
        List<LlmCallEvent> events = new ArrayList<>();

        gateway().chatStreaming(config, request, chunks::add, Cancellation.none(), events::add);

        assertThat(text(chunks)).containsIgnoringCase("blue");
        LlmStreamChunk.Done done = (LlmStreamChunk.Done) chunks.get(chunks.size() - 1);
        assertThat(done.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(done.inputTokens()).isPositive();
        assertThat(done.outputTokens()).isPositive();

        assertThat(events).hasSize(1);
        LlmCallEvent event = events.get(0);
        assertThat(event.modelName()).isEqualTo(config.model());
        assertThat(event.requestJson()).contains("clear daytime sky");
        assertThat(event.responseEvents()).isNotEmpty();
        assertThat(event.errorStatus()).isNull();
        assertThat(event.response().text()).containsIgnoringCase("blue");
    }

    @Test
    void streams_a_tool_call_whose_arguments_parse() {
        LlmConfig config = config();
        ToolDefinition weather = ToolDefinition.of("get_weather", "The current weather in a city",
                Map.of("type", "object",
                        "properties", Map.of("city", Map.of("type", "string", "description", "The city")),
                        "required", List.of("city")));
        LlmRequest request = LlmRequest.streaming(config.name(), List.of(
                LlmMessage.system("Use the get_weather tool for any weather question."),
                LlmMessage.user("What is the weather in Berlin right now?")), List.of(weather));
        List<LlmCallEvent> events = new ArrayList<>();

        LlmResponse response = gateway().chat(config, request, Cancellation.none(), events::add);

        assertThat(response.toolCalls()).hasSize(1);
        ToolCall call = response.toolCalls().get(0);
        assertThat(call.name()).isEqualTo("get_weather");
        assertThat(call.id()).isNotBlank();
        assertThat(String.valueOf(call.arguments().get("city"))).containsIgnoringCase("Berlin");

        assertThat(events).hasSize(1);
        assertThat(events.get(0).response().toolCalls()).extracting(ToolCall::name).containsExactly("get_weather");
    }

    @Test
    void a_rejected_key_fails_with_its_status_and_reaches_the_listener() {
        LlmConfig config = config().withApiKey("not-a-key");
        LlmRequest request = LlmRequest.streaming(config.name(), List.of(LlmMessage.user("hi")));
        List<LlmCallEvent> events = new ArrayList<>();

        assertThatThrownBy(() -> gateway().chatStreaming(config, request, chunk -> { }, Cancellation.none(), events::add))
                .isInstanceOf(RuntimeException.class)
                .hasMessageMatching(".* stream error: 4\\d\\d");

        assertThat(events).isNotEmpty();
        LlmCallEvent event = events.get(events.size() - 1);
        assertThat(event.errorStatus()).isBetween(400, 499);
        assertThat(event.errorBody()).isNotBlank();
        assertThat(event.responseEvents()).isEmpty();
    }

    @Test
    void cancelling_mid_stream_ends_without_a_done_chunk() {
        LlmConfig config = config();
        LlmRequest request = LlmRequest.streaming(config.name(), List.of(
                LlmMessage.user("Count from 1 to 300, one number per line, nothing else.")));
        Cancellation cancellation = Cancellation.none();
        List<LlmStreamChunk> chunks = new ArrayList<>();
        List<LlmCallEvent> events = new ArrayList<>();

        gateway().chatStreaming(config, request, chunk -> {
            chunks.add(chunk);
            if (chunk instanceof LlmStreamChunk.TextDelta) cancellation.cancel();
        }, cancellation, events::add);

        assertThat(chunks).anyMatch(LlmStreamChunk.TextDelta.class::isInstance);
        assertThat(chunks).noneMatch(LlmStreamChunk.Done.class::isInstance);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).errorStatus()).isNull();
    }

    private static String text(List<LlmStreamChunk> chunks) {
        StringBuilder text = new StringBuilder();
        for (LlmStreamChunk chunk : chunks) {
            if (chunk instanceof LlmStreamChunk.TextDelta delta) text.append(delta.text());
        }
        return text.toString();
    }
}
