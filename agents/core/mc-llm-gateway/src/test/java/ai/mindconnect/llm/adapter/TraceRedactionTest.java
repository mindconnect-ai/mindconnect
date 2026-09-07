package ai.mindconnect.llm.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a trace keeps of a request that carries media: everything but the
 * payload. The three providers spell the payload differently — a data URL,
 * a bare base64 field — and prose, however long, is never mistaken for one.
 */
class TraceRedactionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PAYLOAD = "QUJD".repeat(600); // 2,400 base64 chars

    @Test
    void dataUrlsKeepTheirMediaTypeAndLoseTheirPayload() {
        ObjectNode request = JSON.createObjectNode();
        ObjectNode block = request.putArray("messages").addObject().putArray("content").addObject();
        block.put("type", "image_url").putObject("image_url").put("url", "data:image/png;base64," + PAYLOAD);

        JsonNode redacted = TraceRedaction.redactMedia(request);

        assertThat(redacted.at("/messages/0/content/0/image_url/url").asText())
                .isEqualTo("data:image/png;base64,<2400 chars omitted>");
        assertThat(request.at("/messages/0/content/0/image_url/url").asText())
                .as("the wire request is untouched").endsWith(PAYLOAD);
    }

    @Test
    void bareBase64FieldsAreReplaced_whateverTheyAreCalled() {
        ObjectNode request = JSON.createObjectNode();
        request.putObject("source").put("type", "base64").put("media_type", "application/pdf").put("data", PAYLOAD);
        request.putObject("inline_data").put("mime_type", "image/jpeg").put("data", PAYLOAD);

        JsonNode redacted = TraceRedaction.redactMedia(request);

        assertThat(redacted.at("/source/data").asText()).isEqualTo("<base64, 2400 chars omitted>");
        assertThat(redacted.at("/source/media_type").asText()).isEqualTo("application/pdf");
        assertThat(redacted.at("/inline_data/data").asText()).isEqualTo("<base64, 2400 chars omitted>");
    }

    @Test
    void proseAndShortStringsStay() {
        String longPrompt = "You are a helpful assistant. ".repeat(200); // spaces: not base64
        ObjectNode request = JSON.createObjectNode();
        request.put("system", longPrompt);
        request.put("model", "claude-sonnet-4-6");
        request.put("short", "QUJD");

        JsonNode redacted = TraceRedaction.redactMedia(request);

        assertThat(redacted.path("system").asText()).isEqualTo(longPrompt);
        assertThat(redacted.path("model").asText()).isEqualTo("claude-sonnet-4-6");
        assertThat(redacted.path("short").asText()).isEqualTo("QUJD");
    }

    @Test
    void nullIsNull() {
        assertThat(TraceRedaction.redactMedia(null)).isNull();
    }
}
