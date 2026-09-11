package ai.mindconnect.llm.adapter.openai;

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

/**
 * How a user message with media reaches a Chat Completions endpoint: as the
 * content array — and how a text-only one does not: as the plain string it
 * always was, so an endpoint that never learned arrays sees no difference.
 */
class OpenAiGatewayContentBlocksTest {

    private final EncryptionHelper encryption = new EncryptionHelper("0123456789abcdef");
    private final OpenAiCompatibleGateway gateway =
            new OpenAiCompatibleGateway(new OkHttpClient(), new ObjectMapper(), encryption);

    private final LlmConfig config = new LlmConfig(LlmConfigId.random(), "openai", LlmProvider.OPENAI,
            "gpt-5", "https://api.openai.com", "sk-test", 0.7, 4096, Map.of(), 128_000,
            false, null, null, null, null, null);

    @Test
    void textOnlyMessagesStayPlainStrings() throws Exception {
        LlmRequest req = LlmRequest.streaming("openai",
                List.of(LlmMessage.system("be brief"), LlmMessage.user("hi"), LlmMessage.assistant("hello")));

        JsonNode messages = gateway.buildRequestNode(config.resolved(encryption), req).path("messages");

        assertThat(messages).hasSize(3);
        assertThat(messages.get(1).path("content").isTextual()).isTrue();
        assertThat(messages.get(1).path("content").asText()).isEqualTo("hi");
    }

    @Test
    void mediaMessagesBecomeTheContentArray() throws Exception {
        LlmMessage user = LlmMessage.user(List.of(
                new LlmContent.Text("what is this?"),
                new LlmContent.Image("QUJD", "image/png"),
                new LlmContent.Document("UERG", "application/pdf", "spec.pdf")));
        LlmRequest req = LlmRequest.streaming("openai", List.of(user));

        JsonNode content = gateway.buildRequestNode(config.resolved(encryption), req)
                .path("messages").path(0).path("content");

        assertThat(content.isArray()).isTrue();
        assertThat(content).hasSize(3);
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(0).path("text").asText()).isEqualTo("what is this?");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(content.get(1).path("image_url").path("url").asText())
                .isEqualTo("data:image/png;base64,QUJD");
        assertThat(content.get(2).path("type").asText()).isEqualTo("file");
        assertThat(content.get(2).path("file").path("filename").asText()).isEqualTo("spec.pdf");
        assertThat(content.get(2).path("file").path("file_data").asText())
                .isEqualTo("data:application/pdf;base64,UERG");
    }

    @Test
    void aServerWithoutFileBlocksGetsTheDocumentAsANote() throws Exception {
        // LM Studio, Ollama, Groq, … answer an unknown content type with a 400.
        LlmConfig local = new LlmConfig(LlmConfigId.random(), "local", LlmProvider.LM_STUDIO,
                "some-vision-model", "http://localhost:1234", "lm-studio", 0.7, 4096, Map.of(), 128_000,
                false, null, null, null, null, null);
        LlmMessage user = LlmMessage.user(List.of(
                new LlmContent.Text("summarise"),
                new LlmContent.Image("QUJD", "image/png"),
                new LlmContent.Document("UERG", "application/pdf", "spec.pdf")));

        JsonNode content = gateway.buildRequestNode(local.resolved(encryption),
                LlmRequest.streaming("local", List.of(user))).path("messages").path(0).path("content");

        assertThat(content).hasSize(3);
        assertThat(content.get(1).path("type").asText()).as("images are universal").isEqualTo("image_url");
        assertThat(content.get(2).path("type").asText()).isEqualTo("text");
        assertThat(content.get(2).path("text").asText())
                .contains("document attached: spec.pdf (application/pdf)")
                .contains("takes no file blocks");
    }
}
