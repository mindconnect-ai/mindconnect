package ai.mindconnect.llm.adapter.openai;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

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
