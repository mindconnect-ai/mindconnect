package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.ContentPart.MediaSource.FileId;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.management.api.ManagedMessage;
import ai.mindconnect.agent.runtime.tools.attachment.ViewAttachmentTool;
import org.junit.jupiter.api.Test;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A user message's media parts in its bubble: the image itself, a document by name. */
class MessageComponentMediaTest {

    private static final String SESSION_VALUE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final SessionId SESSION = SessionId.of(SESSION_VALUE);

    private static MessageComponent component(ManagedMessage m) {
        return new MessageComponent(SESSION, null, m, true, DateTimeFormatter.ISO_INSTANT);
    }

    /** A user's message as the management reads it from the store. */
    private static ManagedMessage userMessage(int sequence, java.util.Map<String, Object> metadata,
                                              ContentPart... parts) {
        return new ManagedMessage(UUID.randomUUID().toString(), sequence, java.time.Instant.now(),
                new TurnMessage(null, null, TurnMessage.Type.USER, List.of(parts), List.of(), metadata), null, null);
    }

    @Test
    void anImagePartRendersAsThePictureFromTheChatsFileEndpoint() {
        ManagedMessage m = userMessage(1, java.util.Map.of(), new ContentPart.Text("what is this?"),
                new ContentPart.Image(new FileId("f-1"), ContentPart.Image.Detail.AUTO, "my [photo].png"),
                new ContentPart.Document(new FileId("f-2"), "spec.pdf"));

        String body = component(m).withAttachmentChip(m);

        assertThat(body).startsWith("what is this?");
        // The URL carries the bare session id value, never namespace/value.
        String url = "/chat/api/sessions/" + SESSION_VALUE + "/chat-files/f-1/content";
        assertThat(body).contains("[![my photo.png](" + url + ")](" + url + ")");
        assertThat(body).contains("icons.svg#file-text").contains("*spec.pdf*");
        assertThat(body).doesNotContain("📄");
    }

    @Test
    void anAttachmentShownAgainIsOneMutedLineWithoutThumbnailOrActions() throws Exception {
        ManagedMessage m = userMessage(2, java.util.Map.of(
                        ViewAttachmentTool.INSERTED_BY,
                        ViewAttachmentTool.NAME,
                        ViewAttachmentTool.ATTACHMENT, "photo.png"),
                new ContentPart.Text("[photo.png — image shown again at the assistant's request]"),
                new ContentPart.Image(new FileId("f-1"), ContentPart.Image.Detail.AUTO, "photo.png"));

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(component(m).item());

        assertThat(json).contains("shown to the assistant again").contains("reshown-message").contains("icons.svg#repeat");
        assertThat(json).doesNotContain("chat-files/f-1/content").doesNotContain("regen-").doesNotContain("delete-");
    }

    @Test
    void aTextMessageIsItsTextAlone() {
        ManagedMessage m = userMessage(1, java.util.Map.of(), new ContentPart.Text("hello"));

        assertThat(component(m).withAttachmentChip(m)).isEqualTo("hello");
    }
}
