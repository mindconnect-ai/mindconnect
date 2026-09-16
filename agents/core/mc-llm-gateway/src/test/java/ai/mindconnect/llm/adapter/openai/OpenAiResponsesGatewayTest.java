package ai.mindconnect.llm.adapter.openai;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.domain.FinishReason;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.domain.LlmContent;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.domain.ThinkingBlock;
import ai.mindconnect.llm.domain.ToolCall;
import ai.mindconnect.llm.domain.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Responses API on the wire: the history as {@code input} items with the
 * reasoning in front of the calls it led to, and the stream events as the
 * port's chunks. The event payloads are trimmed copies of what gpt-5.6-luna
 * sent.
 */
class OpenAiResponsesGatewayTest {

    private final EncryptionHelper encryption = new EncryptionHelper("0123456789abcdef");
    private final OpenAiResponsesGateway gateway =
            new OpenAiResponsesGateway(new OkHttpClient(), new ObjectMapper(), encryption);

    private LlmConfig config(String model, Map<String, Object> params) {
        return new LlmConfig(LlmConfigId.random(), "openai", LlmProvider.OPENAI,
                model, "https://api.openai.com", "sk-test", 0.7, 4096, params, 128_000,
                false, null, null, null, null, null).resolved(encryption);
    }

    private static final ToolDefinition CALC =
            new ToolDefinition("calc", "evaluate arithmetic", Map.of("type", "object"));

    // ── request ─────────────────────────────────────────────────────────────

    @Test
    void aReasoningModelIsAskedForItsEncryptedReasoningAndGetsNoTemperature() {
        JsonNode root = gateway.buildRequestNode(config("gpt-5.6-luna", Map.of("reasoning_effort", "high")),
                LlmRequest.streaming("openai", List.of(LlmMessage.user("hi")), List.of(CALC)));

        assertThat(root.path("store").asBoolean(true)).isFalse();
        assertThat(root.path("stream").asBoolean()).isTrue();
        assertThat(root.has("temperature")).isFalse();
        assertThat(root.path("max_output_tokens").asInt()).isEqualTo(4096);
        assertThat(root.path("reasoning").path("effort").asText()).isEqualTo("high");
        assertThat(root.path("reasoning").path("summary").asText()).isEqualTo("auto");
        assertThat(root.path("include").get(0).asText()).isEqualTo("reasoning.encrypted_content");
        JsonNode tool = root.path("tools").get(0);
        assertThat(tool.path("type").asText()).isEqualTo("function");
        assertThat(tool.path("name").asText()).isEqualTo("calc");
        assertThat(tool.path("strict").asBoolean(true)).isFalse();
    }

    @Test
    void aModelThatDoesNotReasonGetsTemperatureAndNoReasoning() {
        JsonNode root = gateway.buildRequestNode(config("gpt-4o", Map.of("reasoning_effort", "high")),
                LlmRequest.streaming("openai", List.of(LlmMessage.user("hi"))));

        assertThat(root.path("temperature").asDouble()).isEqualTo(0.7);
        assertThat(root.has("reasoning")).isFalse();
        assertThat(root.has("include")).isFalse();
    }

    @Test
    void summaryNoneLeavesTheSummaryOut() {
        JsonNode root = gateway.buildRequestNode(config("gpt-5.6", Map.of("reasoning_summary", "none")),
                LlmRequest.streaming("openai", List.of(LlmMessage.user("hi"))));

        assertThat(root.path("reasoning").has("summary")).isFalse();
    }

    @Test
    void theHistoryBecomesInputItemsWithTheReasoningInFrontOfItsCalls() {
        List<LlmMessage> history = List.of(
                LlmMessage.system("be brief"),
                LlmMessage.user("what is 2*21?"),
                LlmMessage.assistantWithToolCalls(
                        List.of(new ThinkingBlock("reasoning", "**Calculating**", "gAAAA-enc", null),
                                // another provider's thinking has no business here
                                new ThinkingBlock("thinking", "claude thought", null, "sig")),
                        List.of(new ToolCall("call_1", "calc", Map.of("expr", "2*21")))),
                LlmMessage.tool("call_1", "42"),
                LlmMessage.assistant("42"));

        JsonNode input = gateway.buildRequestNode(config("gpt-5.6", Map.of()),
                LlmRequest.streaming("openai", history, List.of(CALC))).path("input");

        assertThat(input).hasSize(6);
        assertThat(input.get(0).path("role").asText()).isEqualTo("system");
        assertThat(input.get(0).path("content").asText()).isEqualTo("be brief");
        assertThat(input.get(1).path("role").asText()).isEqualTo("user");

        JsonNode reasoning = input.get(2);
        assertThat(reasoning.path("type").asText()).isEqualTo("reasoning");
        assertThat(reasoning.path("encrypted_content").asText()).isEqualTo("gAAAA-enc");
        assertThat(reasoning.path("summary").get(0).path("text").asText()).isEqualTo("**Calculating**");

        JsonNode call = input.get(3);
        assertThat(call.path("type").asText()).isEqualTo("function_call");
        assertThat(call.path("call_id").asText()).isEqualTo("call_1");
        assertThat(call.path("name").asText()).isEqualTo("calc");
        assertThat(call.path("arguments").asText()).isEqualTo("{\"expr\":\"2*21\"}");

        JsonNode output = input.get(4);
        assertThat(output.path("type").asText()).isEqualTo("function_call_output");
        assertThat(output.path("call_id").asText()).isEqualTo("call_1");
        assertThat(output.path("output").asText()).isEqualTo("42");

        assertThat(input.get(5).path("role").asText()).isEqualTo("assistant");
        assertThat(input.get(5).path("content").asText()).isEqualTo("42");
    }

    @Test
    void mediaBecomesInputImageAndInputFile() {
        LlmMessage user = LlmMessage.user(List.of(
                new LlmContent.Text("look"),
                new LlmContent.Image("iVBOR", "image/png"),
                new LlmContent.Document("JVBER", "application/pdf", "a.pdf")));

        JsonNode content = gateway.buildRequestNode(config("gpt-5.6", Map.of()),
                LlmRequest.streaming("openai", List.of(user))).path("input").get(0).path("content");

        assertThat(content.get(0).path("type").asText()).isEqualTo("input_text");
        assertThat(content.get(1).path("type").asText()).isEqualTo("input_image");
        assertThat(content.get(1).path("image_url").asText()).isEqualTo("data:image/png;base64,iVBOR");
        assertThat(content.get(2).path("type").asText()).isEqualTo("input_file");
        assertThat(content.get(2).path("filename").asText()).isEqualTo("a.pdf");
        assertThat(content.get(2).path("file_data").asText()).isEqualTo("data:application/pdf;base64,JVBER");
    }

    // ── stream ──────────────────────────────────────────────────────────────

    @Test
    void reasoningAndParallelToolCallsBecomeChunks() {
        var state = new OpenAiResponsesGateway.StreamState();
        List<LlmStreamChunk> chunks = parse(state,
                "{\"type\":\"response.created\",\"response\":{}}",
                "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\",\"summary\":[]},\"output_index\":0}",
                "{\"type\":\"response.reasoning_summary_part.added\",\"output_index\":0,\"summary_index\":0}",
                "{\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"**Calc**\",\"output_index\":0,\"summary_index\":0}",
                "{\"type\":\"response.reasoning_summary_part.added\",\"output_index\":0,\"summary_index\":1}",
                "{\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"more\",\"output_index\":0,\"summary_index\":1}",
                "{\"type\":\"response.output_item.done\",\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\",\"encrypted_content\":\"gAAAA\"},\"output_index\":0}",
                "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"arguments\":\"\",\"call_id\":\"call_a\",\"name\":\"calc\"},\"output_index\":1}",
                "{\"type\":\"response.function_call_arguments.delta\",\"delta\":\"{\\\"expr\\\":\",\"output_index\":1}",
                "{\"type\":\"response.function_call_arguments.delta\",\"delta\":\"\\\"1+1\\\"}\",\"output_index\":1}",
                "{\"type\":\"response.output_item.done\",\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"arguments\":\"{\\\"expr\\\":\\\"1+1\\\"}\",\"call_id\":\"call_a\",\"name\":\"calc\"},\"output_index\":1}",
                // a call whose arguments only come whole, on done
                "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"fc_2\",\"type\":\"function_call\",\"arguments\":\"\",\"call_id\":\"call_b\",\"name\":\"calc\"},\"output_index\":2}",
                "{\"type\":\"response.output_item.done\",\"item\":{\"id\":\"fc_2\",\"type\":\"function_call\",\"arguments\":\"{\\\"expr\\\":\\\"2+2\\\"}\",\"call_id\":\"call_b\",\"name\":\"calc\"},\"output_index\":2}",
                "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":102,\"output_tokens\":120},\"incomplete_details\":null}}");

        assertThat(chunks).containsExactly(
                new LlmStreamChunk.ThinkingDelta(0, "reasoning", null, null, null),
                new LlmStreamChunk.ThinkingDelta(0, null, "**Calc**", null, null),
                new LlmStreamChunk.ThinkingDelta(0, null, "\n\n", null, null),
                new LlmStreamChunk.ThinkingDelta(0, null, "more", null, null),
                new LlmStreamChunk.ThinkingDelta(0, null, null, null, "gAAAA"),
                new LlmStreamChunk.ToolCallDelta(1, "call_a", "calc", null),
                new LlmStreamChunk.ToolCallDelta(1, null, null, "{\"expr\":"),
                new LlmStreamChunk.ToolCallDelta(1, null, null, "\"1+1\"}"),
                new LlmStreamChunk.ToolCallDelta(2, "call_b", "calc", null),
                new LlmStreamChunk.ToolCallDelta(2, null, null, "{\"expr\":\"2+2\"}"));
        assertThat(state.finish()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(state.inputTokens).isEqualTo(102);
        assertThat(state.outputTokens).isEqualTo(120);
    }

    @Test
    void textEndsWithStopAndAnIncompleteAnswerWithLength() {
        var state = new OpenAiResponsesGateway.StreamState();
        List<LlmStreamChunk> chunks = parse(state,
                "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"msg_1\",\"type\":\"message\"},\"output_index\":0}",
                "{\"type\":\"response.output_text.delta\",\"delta\":\"Hi\",\"output_index\":0}",
                "{\"type\":\"response.output_text.delta\",\"delta\":\"!\",\"output_index\":0}",
                "{\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}}");
        assertThat(chunks).containsExactly(new LlmStreamChunk.TextDelta("Hi"), new LlmStreamChunk.TextDelta("!"));
        assertThat(state.finish()).isEqualTo(FinishReason.STOP);

        var cut = new OpenAiResponsesGateway.StreamState();
        parse(cut, "{\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}");
        assertThat(cut.finish()).isEqualTo(FinishReason.LENGTH);
    }

    @Test
    void aFailedResponseFailsTheCall() {
        assertThatThrownBy(() -> parse(new OpenAiResponsesGateway.StreamState(),
                "{\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"server_error\",\"message\":\"boom\"}}}"))
                .hasMessageContaining("boom");
        assertThatThrownBy(() -> parse(new OpenAiResponsesGateway.StreamState(),
                "{\"type\":\"error\",\"code\":\"invalid\",\"message\":\"bad input\"}"))
                .hasMessageContaining("bad input");
    }

    private List<LlmStreamChunk> parse(OpenAiResponsesGateway.StreamState state, String... payloads) {
        List<LlmStreamChunk> chunks = new ArrayList<>();
        for (String payload : payloads) gateway.parseEvent(payload, state, chunks::add);
        return chunks;
    }
}
