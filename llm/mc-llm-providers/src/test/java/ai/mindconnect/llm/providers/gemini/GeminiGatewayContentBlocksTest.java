package ai.mindconnect.llm.providers.gemini;

import ai.mindconnect.llm.management.api.domain.LlmConfigId;
import ai.mindconnect.llm.providers.TestJson;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.runtime.api.domain.LlmContent;
import ai.mindconnect.llm.runtime.api.domain.LlmMessage;
import ai.mindconnect.llm.management.api.domain.LlmProvider;
import ai.mindconnect.llm.runtime.api.domain.LlmRequest;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A user message with media as Gemini parts: text as {@code text}, media as {@code inline_data}. */
class GeminiGatewayContentBlocksTest {

    private final GeminiGateway gateway =
            new GeminiGateway(new OkHttpClient(), TestJson.MAPPER);

    private final LlmConfig config = new LlmConfig(LlmConfigId.random(), "gemini",
            LlmProvider.GOOGLE_GEMINI, "gemini-2.0-flash", "https://generativelanguage.googleapis.com",
            "key", 0.7, 8192, Map.of(), 1_000_000, false, null, null, null, null, null);

    @Test
    void textOnlyMessagesAreOneTextPart() throws Exception {
        LlmRequest req = LlmRequest.streaming("gemini", List.of(LlmMessage.user("hi")));

        JsonNode parts = gateway.buildRequestNode(config, req)
                .path("contents").path(0).path("parts");

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).path("text").asText()).isEqualTo("hi");
    }

    @Test
    void mediaMessagesBecomeInlineDataParts() throws Exception {
        LlmMessage user = LlmMessage.user(List.of(
                new LlmContent.Text("what is this?"),
                new LlmContent.Image("QUJD", "image/png"),
                new LlmContent.Document("UERG", "application/pdf", "spec.pdf")));
        LlmRequest req = LlmRequest.streaming("gemini", List.of(user));

        JsonNode turn = gateway.buildRequestNode(config, req)
                .path("contents").path(0);
        JsonNode parts = turn.path("parts");

        assertThat(turn.path("role").asText()).isEqualTo("user");
        assertThat(parts).hasSize(3);
        assertThat(parts.get(0).path("text").asText()).isEqualTo("what is this?");
        assertThat(parts.get(1).path("inline_data").path("mime_type").asText()).isEqualTo("image/png");
        assertThat(parts.get(1).path("inline_data").path("data").asText()).isEqualTo("QUJD");
        assertThat(parts.get(2).path("inline_data").path("mime_type").asText()).isEqualTo("application/pdf");
        assertThat(parts.get(2).path("inline_data").path("data").asText()).isEqualTo("UERG");
    }
}
