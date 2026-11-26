package ai.mindconnect.llm.demo.adapter.openaisdk;

import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.management.api.domain.LlmConfigId;
import ai.mindconnect.llm.management.api.domain.LlmProvider;
import ai.mindconnect.llm.runtime.api.domain.Cancellation;
import ai.mindconnect.llm.runtime.api.domain.FinishReason;
import ai.mindconnect.llm.runtime.api.domain.LlmCallEvent;
import ai.mindconnect.llm.runtime.api.domain.LlmMessage;
import ai.mindconnect.llm.runtime.api.domain.LlmRequest;
import ai.mindconnect.llm.runtime.api.domain.LlmResponse;
import ai.mindconnect.llm.runtime.api.domain.LlmStreamChunk;
import ai.mindconnect.llm.runtime.api.domain.LlmTransientException;
import ai.mindconnect.llm.runtime.api.domain.ToolCall;
import ai.mindconnect.llm.runtime.api.domain.ToolDefinition;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against a local server that answers like OpenAI's chat completions endpoint: no network, no cost. */
class OpenAiSdkGatewayTest {

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", status == 200 ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private LlmConfig config() {
        return new LlmConfig(LlmConfigId.random(), "demo", LlmProvider.OPENAI, "gpt-test",
                "http://127.0.0.1:" + server.getAddress().getPort(), "sk-test", 1.0, 256,
                Map.of(), null, false, null, null, null, null, null);
    }

    private static String sse(String... chunks) {
        StringBuilder body = new StringBuilder();
        for (String chunk : chunks) {
            body.append("data: ").append(chunk).append("\n\n");
        }
        return body.append("data: [DONE]\n\n").toString();
    }

    private static String chunk(String choices, String usage) {
        return "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[" + choices + "],\"usage\":" + usage + "}";
    }

    @Test
    void a_streamed_answer_arrives_as_text_deltas_and_ends_with_the_usage() {
        answer = sse(
                chunk("{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Rhine, \"}}", "null"),
                chunk("{\"index\":0,\"delta\":{\"content\":\"Elbe\"},\"finish_reason\":\"stop\"}", "null"),
                chunk("", "{\"prompt_tokens\":12,\"completion_tokens\":3,\"total_tokens\":15}"));
        List<LlmStreamChunk> chunks = new ArrayList<>();
        AtomicReference<LlmCallEvent> event = new AtomicReference<>();

        new OpenAiSdkGateway().chatStreaming(config(),
                LlmRequest.streaming("demo", List.of(LlmMessage.system("Be brief."), LlmMessage.user("Rivers?"))),
                chunks::add, Cancellation.none(), event::set);

        assertThat(chunks).containsExactly(
                new LlmStreamChunk.TextDelta("Rhine, "),
                new LlmStreamChunk.TextDelta("Elbe"),
                new LlmStreamChunk.Done(FinishReason.STOP, 12, 3));
        assertThat(event.get().response().text()).isEqualTo("Rhine, Elbe");
        assertThat(event.get().llmConfigName()).isEqualTo("demo");
        assertThat(event.get().responseEvents()).hasSize(3);
        assertThat(requestBody.get()).contains("\"model\":\"gpt-test\"", "\"stream\":true", "Rivers?", "Be brief.");
    }

    @Test
    void tool_call_fragments_come_back_as_one_call_with_parsed_arguments() {
        answer = sse(
                chunk("{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\"}}]}}", "null"),
                chunk("{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"Bonn\\\"}\"}}]},"
                        + "\"finish_reason\":\"tool_calls\"}", "null"));
        ToolDefinition weather = ToolDefinition.of("weather", "The weather in a city",
                Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))));

        LlmResponse response = new OpenAiSdkGateway().chat(config(),
                LlmRequest.of("demo", List.of(LlmMessage.user("Weather in Bonn?")), List.of(weather)));

        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(response.toolCalls()).containsExactly(new ToolCall("call_1", "weather", Map.of("city", "Bonn")));
        assertThat(requestBody.get()).contains("\"name\":\"weather\"", "\"city\"");
    }

    @Test
    void a_tool_round_trip_is_sent_back_as_assistant_call_and_tool_answer() {
        answer = sse(chunk("{\"index\":0,\"delta\":{\"content\":\"Sunny.\"},\"finish_reason\":\"stop\"}", "null"));

        new OpenAiSdkGateway().chat(config(), LlmRequest.of("demo", List.of(
                LlmMessage.user("Weather in Bonn?"),
                LlmMessage.assistantWithToolCalls(List.of(new ToolCall("call_1", "weather", Map.of("city", "Bonn")))),
                LlmMessage.tool("call_1", "sunny, 21 degrees"))));

        assertThat(requestBody.get()).contains("\"tool_call_id\":\"call_1\"", "sunny, 21 degrees",
                "\"arguments\":\"{\\\"city\\\":\\\"Bonn\\\"}\"");
    }

    @Test
    void a_rate_limit_is_transient_and_still_reaches_the_listener() {
        status = 429;
        answer = "{\"error\":{\"message\":\"slow down\",\"type\":\"rate_limit\"}}";
        AtomicReference<LlmCallEvent> event = new AtomicReference<>();

        assertThatThrownBy(() -> new OpenAiSdkGateway().chatStreaming(config(),
                LlmRequest.streaming("demo", List.of(LlmMessage.user("Hi"))), chunk -> { },
                Cancellation.none(), event::set))
                .isInstanceOf(LlmTransientException.class)
                .satisfies(e -> assertThat(((LlmTransientException) e).status()).isEqualTo(429));
        assertThat(event.get().errorStatus()).isEqualTo(429);
        assertThat(event.get().response()).isNull();
    }
}
