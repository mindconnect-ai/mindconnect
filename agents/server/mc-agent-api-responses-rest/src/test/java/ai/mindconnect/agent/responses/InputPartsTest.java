package ai.mindconnect.agent.responses;

import ai.mindconnect.agent.protocol.item.ContentPart;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A user message's parts, as OpenAI spells them: text, an image by data URL
 * or file id, a file by data, id or URL — and nothing else.
 */
class InputPartsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PNG_B64 = Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
    private static final String PDF_B64 = Base64.getEncoder().encodeToString("%PDF-1.4".getBytes());

    @Test
    void textImageAndFilePartsBecomeProtocolParts() throws Exception {
        var content = JSON.readTree("""
                [
                  {"type": "input_text", "text": "What is this?"},
                  {"type": "input_image", "image_url": "data:image/png;base64,%s", "detail": "high"},
                  {"type": "input_image", "file_id": "file-7"},
                  {"type": "input_file", "filename": "spec.pdf", "file_data": "data:application/pdf;base64,%s"},
                  {"type": "input_file", "file_id": "file-9"}
                ]
                """.formatted(PNG_B64, PDF_B64));

        List<ContentPart> parts = InputParts.parse(content);

        assertThat(parts).hasSize(5);
        assertThat(parts.get(0)).isEqualTo(new ContentPart.Text("What is this?"));
        assertThat(parts.get(1)).isEqualTo(new ContentPart.Image(
                new ContentPart.MediaSource.Inline(PNG_B64, "image/png"), ContentPart.Image.Detail.HIGH));
        assertThat(parts.get(2)).isEqualTo(new ContentPart.Image(
                new ContentPart.MediaSource.FileId("file-7"), ContentPart.Image.Detail.AUTO));
        assertThat(parts.get(3)).isEqualTo(new ContentPart.Document(
                new ContentPart.MediaSource.Inline(PDF_B64, "application/pdf"), "spec.pdf"));
        assertThat(parts.get(4)).isEqualTo(new ContentPart.Document(
                new ContentPart.MediaSource.FileId("file-9"), null));
    }

    @Test
    void aStringIsOneTextPart_andTheChatCompletionsImageSpellingIsMet() throws Exception {
        assertThat(InputParts.parse(JSON.readTree("\"hello\""))).containsExactly(new ContentPart.Text("hello"));
        assertThat(InputParts.parse(null)).isEmpty();
        var parts = InputParts.parse(JSON.readTree(
                "[{\"type\":\"input_image\",\"image_url\":{\"url\":\"data:image/jpeg;base64," + PNG_B64 + "\"}}]"));
        assertThat(parts.get(0)).isEqualTo(new ContentPart.Image(
                new ContentPart.MediaSource.Inline(PNG_B64, "image/jpeg"), ContentPart.Image.Detail.AUTO));
    }

    @Test
    void whatTheServerCannotTakeIsRefused_notDropped() throws Exception {
        assertThatThrownBy(() -> InputParts.parse(JSON.readTree("[{\"type\":\"input_audio\",\"input_audio\":{}}]")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("input_audio");
        assertThatThrownBy(() -> InputParts.parse(JSON.readTree("[{\"text\":\"no type\"}]")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'type'");
        assertThatThrownBy(() -> InputParts.parse(JSON.readTree("[{\"type\":\"input_file\",\"file_data\":\"data:text/plain;base64,aGk=\"}]")))
                .as("file_data without a name").isInstanceOf(IllegalArgumentException.class).hasMessageContaining("filename");
        assertThatThrownBy(() -> InputParts.parse(JSON.readTree("[{\"type\":\"input_image\"}]")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("image_url");
        assertThatThrownBy(() -> InputParts.parse(JSON.readTree("[{\"type\":\"input_file\",\"filename\":\"a.pdf\",\"file_data\":\"data:application/pdf,notbase64\"}]")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("base64");
        assertThatThrownBy(() -> InputParts.fetched("ftp://example.com/x.pdf", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("http(s)");
    }

    @Test
    void theMapperPutsThePartsIntoAUserMessage_andRefusesOtherRoles() throws Exception {
        var mapper = new ResponsesMapper(JSON);
        var items = mapper.toItems(JSON.readTree("""
                [{"role": "user", "content": [
                    {"type": "input_text", "text": "Read this"},
                    {"type": "input_file", "filename": "notes.txt", "file_data": "data:text/plain;base64,aGk="}
                ]}]
                """));
        assertThat(items).hasSize(1);
        var message = (ConversationItem.Message) items.get(0);
        assertThat(message.role()).isEqualTo(Role.USER);
        assertThat(message.content()).containsExactly(
                new ContentPart.Text("Read this"),
                new ContentPart.Document(new ContentPart.MediaSource.Inline("aGk=", "text/plain"), "notes.txt"));

        assertThatThrownBy(() -> mapper.toItems(JSON.readTree("[{\"role\":\"assistant\",\"content\":\"earlier\"}]")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("previous_response_id");
        assertThatThrownBy(() -> mapper.toItems(JSON.readTree("[{\"type\":\"input_text\",\"text\":\"bare part\"}]")))
                .as("a part outside a message").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mediaTypesFollowTheName_andADataUrlMayOmitItsType() {
        assertThat(InputParts.mediaTypeFor("x.PDF")).isEqualTo("application/pdf");
        assertThat(InputParts.mediaTypeFor("logo.svg")).isEqualTo("image/svg+xml");
        assertThat(InputParts.mediaTypeFor("noext")).isEqualTo("application/octet-stream");
        assertThat(InputParts.dataUrl("data:;base64,aGk=")).isEqualTo(new ContentPart.MediaSource.Inline("aGk=", "application/octet-stream"));
        assertThat(InputParts.nameFrom("https://x.example/docs/spec.pdf?x=1")).isEqualTo("spec.pdf");
        assertThat(InputParts.nameFrom("https://x.example/")).isEqualTo("download");
    }
}
