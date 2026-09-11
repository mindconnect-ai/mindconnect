package ai.mindconnect.llm.adapter.anthropic;

import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmContent;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.domain.LlmRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A user message with media as Anthropic content blocks; text-only stays a string. */
class ClaudeGatewayContentBlocksTest {

    private final EncryptionHelper encryption = new EncryptionHelper("0123456789abcdef");
    private final ClaudeGateway gateway =
            new ClaudeGateway(new OkHttpClient(), new ObjectMapper(), encryption);

    private final LlmConfig config = new LlmConfig(LlmConfigId.random(), "claude", LlmProvider.ANTHROPIC,
            "claude-sonnet-4-6", "https://api.anthropic.com", "sk-test", 0.7, 8192, Map.of(), 200_000,
            false, null, null, null, null, null);

    @Test
    void textOnlyMessagesStayPlainStrings() throws Exception {
        LlmRequest req = LlmRequest.streaming("claude",
                List.of(LlmMessage.system("be brief"), LlmMessage.user("hi")));

        JsonNode root = gateway.buildRequestNode(config.resolved(encryption), req);

        assertThat(root.path("system").asText()).isEqualTo("be brief");
        assertThat(root.path("messages").path(0).path("content").isTextual()).isTrue();
        assertThat(root.path("messages").path(0).path("content").asText()).isEqualTo("hi");
    }

    @Test
    void mediaMessagesBecomeContentBlocksWithBase64Sources() throws Exception {
        LlmMessage user = LlmMessage.user(List.of(
                new LlmContent.Text("what is this?"),
                new LlmContent.Image("QUJD", "image/png"),
                new LlmContent.Document("UERG", "application/pdf", "spec.pdf")));
        LlmRequest req = LlmRequest.streaming("claude", List.of(user));

        JsonNode message = gateway.buildRequestNode(config.resolved(encryption), req)
                .path("messages").path(0);
        JsonNode content = message.path("content");

        assertThat(message.path("role").asText()).isEqualTo("user");
        assertThat(content.isArray()).isTrue();
        assertThat(content).hasSize(3);
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(0).path("text").asText()).isEqualTo("what is this?");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image");
        assertThat(content.get(1).path("source").path("type").asText()).isEqualTo("base64");
        assertThat(content.get(1).path("source").path("media_type").asText()).isEqualTo("image/png");
        assertThat(content.get(1).path("source").path("data").asText()).isEqualTo("QUJD");
        assertThat(content.get(2).path("type").asText()).isEqualTo("document");
        assertThat(content.get(2).path("source").path("media_type").asText()).isEqualTo("application/pdf");
        assertThat(content.get(2).path("source").path("data").asText()).isEqualTo("UERG");
        assertThat(content.get(2).path("title").asText()).isEqualTo("spec.pdf");
    }
}
