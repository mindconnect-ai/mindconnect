package ai.mindconnect.llm.adapter.anthropic;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.ThinkingBlock;
import ai.mindconnect.llm.domain.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the generic additionalParams channel renders the correct
 * Anthropic wire fields (thinking / output_config.effort) and that temperature
 * is suppressed when adaptive thinking is on (Opus 4.7/4.8 reject sampling
 * params under adaptive thinking).
 */
class ClaudeGatewayThinkingTest {

    private final EncryptionHelper encryption = new EncryptionHelper("0123456789abcdef");
    private final ObjectMapper mapper = new ObjectMapper();
    private final ClaudeGateway gateway =
            new ClaudeGateway(new OkHttpClient(), mapper, encryption);

    private LlmConfig config(Map<String, Object> additionalParams) {
        return new LlmConfig(java.util.UUID.randomUUID(), "claude", LlmProvider.ANTHROPIC,
                "claude-opus-4-8", "https://api.anthropic.com", "sk-test",
                0.7, 8192, additionalParams, 200_000, false, null, null, null, null);
    }

    @Test
    void adaptiveThinking_rendersThinkingAndEffort_andSuppressesTemperature() throws Exception {
        LlmConfig cfg = config(Map.of("thinking", "adaptive", "effort", "high",
                "thinkingDisplay", "summarized"));
        LlmRequest req = LlmRequest.streaming("claude", List.of(LlmMessage.user("hi")));

        JsonNode root = gateway.buildRequestNode(cfg.resolved(encryption), req);

        assertThat(root.path("thinking").path("type").asText()).isEqualTo("adaptive");
        assertThat(root.path("thinking").path("display").asText()).isEqualTo("summarized");
        assertThat(root.path("output_config").path("effort").asText()).isEqualTo("high");
        assertThat(root.has("temperature")).isFalse();
    }

    @Test
    void requestParamsOverrideConfig() throws Exception {
        LlmConfig cfg = config(Map.of("thinking", "adaptive", "effort", "high"));
        LlmRequest req = LlmRequest.streaming("claude", List.of(LlmMessage.user("hi")))
                .withAdditionalParams(Map.of("effort", "low"));

        JsonNode root = gateway.buildRequestNode(cfg.resolved(encryption), req);

        assertThat(root.path("output_config").path("effort").asText()).isEqualTo("low");
    }

    @Test
    void noThinkingParams_keepsTemperature_andOmitsThinking() throws Exception {
        LlmConfig cfg = config(Map.of());
        LlmRequest req = LlmRequest.streaming("claude", List.of(LlmMessage.user("hi")));

        JsonNode root = gateway.buildRequestNode(cfg.resolved(encryption), req);

        assertThat(root.has("thinking")).isFalse();
        assertThat(root.has("output_config")).isFalse();
        assertThat(root.has("temperature")).isTrue();
    }

    @Test
    void thinkingDisabled_rendersDisabled_andKeepsTemperature() throws Exception {
        LlmConfig cfg = config(Map.of("thinking", "disabled"));
        LlmRequest req = LlmRequest.streaming("claude", List.of(LlmMessage.user("hi")));

        JsonNode root = gateway.buildRequestNode(cfg.resolved(encryption), req);

        assertThat(root.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(root.has("temperature")).isTrue();
    }

    @Test
    void replay_rendersThinkingBlocksBeforeToolUse() throws Exception {
        LlmConfig cfg = config(Map.of("thinking", "adaptive"));
        // An assistant turn carrying a thinking block + two tool calls, as it
        // would be reconstructed from history before a follow-up request.
        LlmMessage assistant = LlmMessage.assistantWithToolCalls(
                List.of(new ThinkingBlock("thinking",
                        "User wants two cities. I'll call get_weather twice.",
                        null, "SIG-abc==")),
                List.of(new ToolCall("toolu_01A", "get_weather", Map.of("city", "Berlin"), null),
                        new ToolCall("toolu_01B", "get_weather", Map.of("city", "Hamburg"), null)));
        LlmRequest req = LlmRequest.streaming("claude",
                List.of(LlmMessage.user("weather?"), assistant));

        JsonNode root = gateway.buildRequestNode(cfg.resolved(encryption), req);

        // messages[1] is the assistant turn; its content must be
        // [thinking, tool_use, tool_use] in that exact order.
        JsonNode content = root.path("messages").path(1).path("content");
        assertThat(content.get(0).path("type").asText()).isEqualTo("thinking");
        assertThat(content.get(0).path("thinking").asText())
                .isEqualTo("User wants two cities. I'll call get_weather twice.");
        assertThat(content.get(0).path("signature").asText()).isEqualTo("SIG-abc==");
        assertThat(content.get(1).path("type").asText()).isEqualTo("tool_use");
        assertThat(content.get(1).path("id").asText()).isEqualTo("toolu_01A");
        assertThat(content.get(2).path("type").asText()).isEqualTo("tool_use");
        assertThat(content.get(2).path("id").asText()).isEqualTo("toolu_01B");
    }

    @Test
    void replay_rendersRedactedThinkingBlock() throws Exception {
        LlmConfig cfg = config(Map.of("thinking", "adaptive"));
        LlmMessage assistant = LlmMessage.assistantWithToolCalls(
                List.of(new ThinkingBlock("redacted_thinking", null, "ENCRYPTED==", null)),
                List.of(new ToolCall("toolu_01A", "get_weather", Map.of("city", "Berlin"), null)));
        LlmRequest req = LlmRequest.streaming("claude",
                List.of(LlmMessage.user("weather?"), assistant));

        JsonNode content = gateway.buildRequestNode(cfg.resolved(encryption), req)
                .path("messages").path(1).path("content");

        assertThat(content.get(0).path("type").asText()).isEqualTo("redacted_thinking");
        assertThat(content.get(0).path("data").asText()).isEqualTo("ENCRYPTED==");
        assertThat(content.get(0).has("thinking")).isFalse();
        assertThat(content.get(1).path("type").asText()).isEqualTo("tool_use");
    }
}
