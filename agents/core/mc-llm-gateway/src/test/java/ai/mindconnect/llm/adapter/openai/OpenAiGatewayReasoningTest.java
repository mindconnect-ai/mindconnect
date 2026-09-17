package ai.mindconnect.llm.adapter.openai;

import ai.mindconnect.common.Cancellation;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.domain.ToolDefinition;
import ai.mindconnect.llm.port.in.LlmCallListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reasoning on an OpenAI-compatible endpoint: the effort goes out as
 * {@code reasoning_effort}, and the model's thoughts come back as thinking
 * deltas from whichever field the server puts them in.
 */
class OpenAiGatewayReasoningTest {

    private final EncryptionHelper encryption = new EncryptionHelper("0123456789abcdef");
    private final OpenAiCompatibleGateway gateway =
            new OpenAiCompatibleGateway(new OkHttpClient(), new ObjectMapper(), encryption);

    private LlmConfig config(Map<String, Object> additionalParams) {
        return new LlmConfig(LlmConfigId.random(), "local", LlmProvider.LM_STUDIO,
                "qwen3-8b", "http://localhost:1234", "lm-studio", 0.7, 4096, additionalParams, 32_000,
                false, null, null, null, null, null);
    }

    private final LlmRequest request = LlmRequest.streaming("local", List.of(LlmMessage.user("hi")));

    @Test
    void reasoningEffortFromTheConfigGoesOnTheWire() throws Exception {
        JsonNode root = gateway.buildRequestNode(config(Map.of("reasoning_effort", "high")).resolved(encryption), request);
        assertThat(root.path("reasoning_effort").asText()).isEqualTo("high");
    }

    @Test
    void noEffortNoField() throws Exception {
        assertThat(gateway.buildRequestNode(config(Map.of()).resolved(encryption), request)
                .has("reasoning_effort")).isFalse();
        assertThat(gateway.buildRequestNode(config(Map.of("reasoning_effort", " ")).resolved(encryption), request)
                .has("reasoning_effort")).isFalse();
    }

    @Test
    void theRequestOverridesTheConfig() throws Exception {
        LlmRequest perRequest = new LlmRequest("local", List.of(LlmMessage.user("hi")), List.of(),
                -1, -1, true, Map.of("reasoning_effort", "low"));
        JsonNode root = gateway.buildRequestNode(config(Map.of("reasoning_effort", "high")).resolved(encryption), perRequest);
        assertThat(root.path("reasoning_effort").asText()).isEqualTo("low");
    }

    @Test
    void openAiGetsTheFieldOnlyWithAReasoningModel() throws Exception {
        LlmConfig nonReasoning = new LlmConfig(LlmConfigId.random(), "openai", LlmProvider.OPENAI,
                "gpt-4o", "https://api.openai.com", "sk-test", 0.7, 4096,
                Map.of("reasoning_effort", "high"), 128_000, false, null, null, null, null, null);
        assertThat(gateway.buildRequestNode(nonReasoning.resolved(encryption), request)
                .has("reasoning_effort")).isFalse();

        LlmConfig reasoning = new LlmConfig(LlmConfigId.random(), "openai", LlmProvider.OPENAI,
                "gpt-5", "https://api.openai.com", "sk-test", 0.7, 4096,
                Map.of("reasoning_effort", "high"), 128_000, false, null, null, null, null, null);
        assertThat(gateway.buildRequestNode(reasoning.resolved(encryption), request)
                .path("reasoning_effort").asText()).isEqualTo("high");
    }

    @Test
    void fromGpt56OnChatCompletionsToolsForceTheEffortToNone() throws Exception {
        LlmConfig gpt56 = new LlmConfig(LlmConfigId.random(), "openai", LlmProvider.OPENAI_CHAT_COMPLETIONS,
                "gpt-5.6-luna", "https://api.openai.com", "sk-test", 0.7, 4096,
                Map.of("reasoning_effort", "high"), 128_000, false, null, null, null, null, null).resolved(encryption);
        LlmRequest withTools = new LlmRequest("openai", List.of(LlmMessage.user("hi")),
                List.of(new ToolDefinition("get_time", "now", Map.of("type", "object"))),
                -1, -1, true, Map.of());
        assertThat(gateway.buildRequestNode(gpt56, withTools).path("reasoning_effort").asText()).isEqualTo("none");
        // without tools the configured effort stays
        assertThat(gateway.buildRequestNode(gpt56, request).path("reasoning_effort").asText()).isEqualTo("high");
    }

    @Test
    void onlyGpt56AndLaterRefuseToolsWithReasoning() {
        assertThat(OpenAiModels.refusesToolsWithReasoningOnChat("gpt-5.6")).isTrue();
        assertThat(OpenAiModels.refusesToolsWithReasoningOnChat("gpt-5.6-luna")).isTrue();
        assertThat(OpenAiModels.refusesToolsWithReasoningOnChat("gpt-5.10-mini")).isTrue();
        assertThat(OpenAiModels.refusesToolsWithReasoningOnChat("gpt-6")).isTrue();
        assertThat(OpenAiModels.refusesToolsWithReasoningOnChat("gpt-5.5")).isFalse();
        assertThat(OpenAiModels.refusesToolsWithReasoningOnChat("gpt-5-mini")).isFalse();
        assertThat(OpenAiModels.refusesToolsWithReasoningOnChat("gpt-4o")).isFalse();
        assertThat(OpenAiModels.refusesToolsWithReasoningOnChat("o3")).isFalse();
    }

    @Test
    void openAiThroughChatCompletionsIsStillOpenAi() throws Exception {
        LlmConfig nonReasoning = new LlmConfig(LlmConfigId.random(), "openai", LlmProvider.OPENAI_CHAT_COMPLETIONS,
                "gpt-4o", null, "sk-test", 0.7, 4096,
                Map.of("reasoning_effort", "high"), 128_000, false, null, null, null, null, null);
        assertThat(gateway.buildRequestNode(nonReasoning.resolved(encryption), request)
                .has("reasoning_effort")).isFalse();
        assertThat(gateway.endpointUrl(nonReasoning)).isEqualTo("https://api.openai.com/v1/chat/completions");
        // the summary is a Responses API setting — nothing to offer here
        assertThat(LlmProvider.OPENAI_CHAT_COMPLETIONS.additionalParams())
                .extracting(ai.mindconnect.llm.domain.AdditionalParamSpec::key)
                .contains("reasoning_effort").doesNotContain("reasoning_summary");
        assertThat(LlmProvider.OPENAI.additionalParams())
                .extracting(ai.mindconnect.llm.domain.AdditionalParamSpec::key)
                .contains("reasoning_effort", "reasoning_summary");
    }

    @Test
    void reasoningContentFieldBecomesAThinkingDelta() {
        List<LlmStreamChunk> chunks = parse(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"Let me see\"}}]}",
                "{\"choices\":[{\"delta\":{\"reasoning_content\":null,\"content\":\"42\"}}]}");
        assertThat(chunks).containsExactly(
                new LlmStreamChunk.ThinkingDelta(0, "thinking", "Let me see", null, null),
                new LlmStreamChunk.TextDelta("42"));
    }

    @Test
    void reasoningFieldBecomesAThinkingDeltaToo() {
        List<LlmStreamChunk> chunks = parse("{\"choices\":[{\"delta\":{\"reasoning\":\"hmm\"}}]}");
        assertThat(chunks).containsExactly(
                new LlmStreamChunk.ThinkingDelta(0, "thinking", "hmm", null, null));
    }

    @Test
    void inlineThinkTagsAreCutOutOfTheContent() {
        List<LlmStreamChunk> chunks = parse(
                "{\"choices\":[{\"delta\":{\"content\":\"<think>step one\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\" step two</think>The\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\" answer\"}}]}");
        assertThat(chunks).containsExactly(
                new LlmStreamChunk.ThinkingDelta(0, "thinking", "step one", null, null),
                new LlmStreamChunk.ThinkingDelta(0, "thinking", " step two", null, null),
                new LlmStreamChunk.TextDelta("The"),
                new LlmStreamChunk.TextDelta(" answer"));
    }

    @Test
    void aServerThatRefusesTheEffortGetsTheRequestAgainWithoutIt() throws Exception {
        // gpt-5.4-mini with function tools on Chat Completions: below the gpt-5.6 line of the model rules,
        // so the field goes out, and OpenAI refuses it.
        String refusal = "{\"error\":{\"message\":\"Function tools with reasoning_effort are not supported for "
                + "gpt-5.4-mini in /v1/chat/completions. To use function tools, use /v1/responses or set "
                + "reasoning_effort to 'none'.\",\"type\":\"invalid_request_error\",\"param\":\"reasoning_effort\"}}";
        String stream = "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = server(bodies, List.of(new Answer(400, "application/json", refusal),
                new Answer(200, "text/event-stream", stream)));
        try {
            List<LlmStreamChunk> chunks = new ArrayList<>();
            gateway.chatStreaming(miniWithTools(server).resolved(encryption), withTools(), chunks::add,
                    Cancellation.none(), LlmCallListener.NOOP);

            assertThat(bodies).hasSize(2);
            assertThat(bodies.get(0)).contains("\"reasoning_effort\":\"medium\"");
            assertThat(bodies.get(1)).doesNotContain("reasoning_effort").contains("get_weather");
            assertThat(chunks).contains(new LlmStreamChunk.TextDelta("hi"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anotherBadRequestIsNotRetried() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = server(bodies, List.of(new Answer(400, "application/json",
                "{\"error\":{\"message\":\"Invalid value for messages\",\"param\":\"messages\"}}")));
        try {
            assertThatThrownBy(() -> gateway.chatStreaming(
                    miniWithTools(server).resolved(encryption), withTools(), chunk -> { },
                    Cancellation.none(), LlmCallListener.NOOP))
                    .hasMessageContaining("400");
            assertThat(bodies).hasSize(1);
        } finally {
            server.stop(0);
        }
    }

    private static LlmConfig miniWithTools(HttpServer server) {
        return new LlmConfig(LlmConfigId.random(), "openai", LlmProvider.OPENAI_CHAT_COMPLETIONS,
                "gpt-5.4-mini", "http://127.0.0.1:" + server.getAddress().getPort(), "sk-test", 0.7, 4096,
                Map.of("reasoning_effort", "medium"), 128_000, false, null, null, null, null, null);
    }

    private static LlmRequest withTools() {
        return LlmRequest.streaming("openai", List.of(LlmMessage.user("hi")),
                List.of(ToolDefinition.of("get_weather", "weather", Map.of("type", "object"))));
    }

    private record Answer(int status, String contentType, String body) {}

    /** Answers the chat completions endpoint with {@code answers} in turn, recording each request body. */
    private static HttpServer server(List<String> bodies, List<Answer> answers) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Answer answer = answers.get(Math.min(bodies.size(), answers.size()) - 1);
            byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", answer.contentType());
            exchange.sendResponseHeaders(answer.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    private List<LlmStreamChunk> parse(String... payloads) {
        List<LlmStreamChunk> chunks = new ArrayList<>();
        Map<Integer, AbstractOpenAiGateway.OpenAiToolCallBuilder> builders = new TreeMap<>();
        ThinkTagSplitter splitter = new ThinkTagSplitter();
        for (String payload : payloads) {
            gateway.parseOpenAiDelta(payload, builders, splitter, chunks::add);
        }
        return chunks;
    }
}
